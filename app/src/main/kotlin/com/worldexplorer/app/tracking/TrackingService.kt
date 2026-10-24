package com.worldexplorer.app.tracking

import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.worldexplorer.app.R
import com.worldexplorer.app.di.ApplicationScope
import com.worldexplorer.domain.model.GpsSignal
import com.worldexplorer.domain.model.TrackStatus
import com.worldexplorer.domain.model.TrackingState
import com.worldexplorer.domain.repository.LocationRepository
import com.worldexplorer.domain.repository.SettingsRepository
import com.worldexplorer.domain.repository.TrackRepository
import com.worldexplorer.domain.result.AppError
import com.worldexplorer.domain.tracking.Clock
import com.worldexplorer.domain.tracking.RecordOutcome
import com.worldexplorer.domain.tracking.TrackRecorder
import com.worldexplorer.domain.usecase.StopTrackingUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Foreground service (type "location") that records the active track. It only runs after an
 * explicit user action and always shows an ongoing notification with a Stop action.
 *
 * Lifecycle:
 * - ACTION_START (or a sticky restart with a null intent): enter the foreground immediately, attach
 *   to the ACTIVE track in the database and stream location fixes into [TrackRecorder].
 * - ACTION_STOP (notification button): runs [StopTrackingUseCase], which closes the track and
 *   stops this service.
 * - onDestroy: stops location updates and flushes counters in the application scope.
 * Duplicate starts are ignored; the database guarantees a single ACTIVE track.
 */
@AndroidEntryPoint
class TrackingService : LifecycleService() {
    @Inject lateinit var tracks: TrackRepository
    @Inject lateinit var location: LocationRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var controller: TrackingServiceController
    @Inject lateinit var recorderFactory: TrackRecorder.Factory
    @Inject lateinit var stopTracking: StopTrackingUseCase
    @Inject lateinit var clock: Clock
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    private var recording: Job? = null
    private var recorder: TrackRecorder? = null
    private var failure: String? = null
    private var lastNotificationAt = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            appScope.launch { stopTracking() }
            return START_NOT_STICKY
        }
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        controller.onServiceStarted()
        if (recording == null) recording = lifecycleScope.launch { record() }
        // Sticky: if the system kills the process, it recreates the service and recording resumes.
        return START_STICKY
    }

    private fun enterForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, TrackingNotifications.ID_TRACKING, TrackingNotifications.tracking(this, 0.0, 0, TrackingNotifications.Signal.WAITING, null), type)
        true
    } catch (e: Exception) {
        // SecurityException (no location permission) or ForegroundServiceStartNotAllowedException
        // (restart from background without "Allow all the time").
        Log.e(TAG, "cannot enter foreground", e)
        failure = e.javaClass.simpleName
        TrackingNotifications.showInterrupted(this)
        false
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun record() {
        val track = tracks.getActiveTrack()
        if (track == null) {
            Log.i(TAG, "no active track, stopping service")
            stopSelf()
            return
        }
        val session = recorderFactory.create().also { recorder = it }
        session.attach(track.id)
        var distance = track.distanceMeters
        var points = track.pointCount
        var lastFixAt: Long? = null
        val current = settings.settings.stateIn(lifecycleScope, SharingStarted.Eagerly, settings.current())
        val gps = location.observeGpsStatus().stateIn(lifecycleScope, SharingStarted.Eagerly, null)

        fun publish(signal: GpsSignal) {
            controller.update(TrackingState.Recording(track.id, track.startedAtUtc, signal, lastFixAt))
            val now = clock.nowUtcMillis()
            if (now - lastNotificationAt < NOTIFICATION_THROTTLE_MILLIS && signal == GpsSignal.OK) return
            lastNotificationAt = now
            val n = when {
                gps.value?.providerEnabled == false -> TrackingNotifications.Signal.PROVIDER_OFF
                signal == GpsSignal.LOST -> TrackingNotifications.Signal.LOST
                signal == GpsSignal.WAITING -> TrackingNotifications.Signal.WAITING
                else -> TrackingNotifications.Signal.OK
            }
            getSystemService(NotificationManager::class.java)
                .notify(TrackingNotifications.ID_TRACKING, TrackingNotifications.tracking(this, distance, points, n, track.startedAtUtc))
        }
        publish(GpsSignal.WAITING)

        // Watchdog: report signal loss (tunnels, indoors) and provider changes without stopping.
        lifecycleScope.launch {
            var lastSignal: GpsSignal? = null
            while (isActive) {
                val age = lastFixAt?.let { clock.nowUtcMillis() - it }
                val signal = when {
                    lastFixAt == null -> GpsSignal.WAITING
                    age != null && age > SIGNAL_LOST_AFTER_MILLIS -> GpsSignal.LOST
                    else -> GpsSignal.OK
                }
                if (signal != lastSignal || gps.value?.providerEnabled == false) {
                    lastNotificationAt = 0
                    publish(signal)
                    lastSignal = signal
                }
                delay(WATCHDOG_MILLIS)
            }
        }

        val interval = MutableStateFlow(current.value.trackingProfile.movingIntervalMillis)
        try {
            interval.flatMapLatest { location.locationUpdates(it) }.collect { sample ->
                val outcome = withContext(NonCancellable) { session.onSample(sample, current.value.exploreRadiusMeters) }
                when (outcome) {
                    is RecordOutcome.Recorded -> {
                        lastFixAt = clock.nowUtcMillis()
                        tracks.getTrack(track.id)?.let { distance = it.distanceMeters; points = it.pointCount }
                        publish(GpsSignal.OK)
                    }
                    is RecordOutcome.Rejected -> lastFixAt = clock.nowUtcMillis()
                    is RecordOutcome.Failed -> if (outcome.error == AppError.NotTracking) {
                        Log.i(TAG, "track closed elsewhere, stopping")
                        stopSelf()
                        return@collect
                    } else {
                        Log.e(TAG, "recording failed: ${outcome.error}")
                    }
                }
                interval.value = session.desiredIntervalMillis(current.value.trackingProfile)
            }
        } catch (e: SecurityException) {
            // Permission revoked while recording (usually the system kills the process instead).
            Log.e(TAG, "location permission lost during recording", e)
            failure = "permission"
            withContext(NonCancellable) { tracks.finishTrack(track.id, lastFixAt ?: clock.nowUtcMillis(), TrackStatus.INTERRUPTED) }
            TrackingNotifications.showInterrupted(this, getString(R.string.notif_permission_lost))
            stopSelf()
        }
    }

    override fun onDestroy() {
        recording?.cancel()
        val session = recorder
        // Final write must not be cancelled together with the service scope.
        appScope.launch { session?.flushRejected() }
        controller.onServiceStopped(failure)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.worldexplorer.app.tracking.START"
        const val ACTION_STOP = "com.worldexplorer.app.tracking.STOP"
        private const val TAG = "WorldExplorer"
        private const val SIGNAL_LOST_AFTER_MILLIS = 45_000L
        private const val WATCHDOG_MILLIS = 5_000L
        private const val NOTIFICATION_THROTTLE_MILLIS = 5_000L
    }
}

