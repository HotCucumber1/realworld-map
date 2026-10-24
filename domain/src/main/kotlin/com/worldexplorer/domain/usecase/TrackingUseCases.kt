package com.worldexplorer.domain.usecase

import com.worldexplorer.domain.model.Track
import com.worldexplorer.domain.model.TrackStatus
import com.worldexplorer.domain.model.TrackingState
import com.worldexplorer.domain.repository.LocationRepository
import com.worldexplorer.domain.repository.SettingsRepository
import com.worldexplorer.domain.repository.SyncScheduler
import com.worldexplorer.domain.repository.TrackRepository
import com.worldexplorer.domain.repository.TrackingController
import com.worldexplorer.domain.result.AppError
import com.worldexplorer.domain.result.AppResult
import com.worldexplorer.domain.tracking.Clock
import com.worldexplorer.domain.tracking.IdGenerator
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Explicit user action: create a new active track and start the recording service. */
class StartTrackingUseCase @Inject constructor(
    private val tracks: TrackRepository,
    private val controller: TrackingController,
    private val location: LocationRepository,
    private val clock: Clock,
    private val ids: IdGenerator,
) {
    suspend operator fun invoke(): AppResult<Track> {
        if (!location.permissionState().anyLocation) return AppResult.Failure(AppError.LocationPermissionDenied)
        if (!location.observeGpsStatus().first().providerEnabled) return AppResult.Failure(AppError.LocationDisabled)

        tracks.getActiveTrack()?.let { active ->
            if (controller.isRecorderRunning) return AppResult.Failure(AppError.AlreadyTracking)
            // An active track survived a process death: continue it instead of opening a second one.
            return when (val started = controller.startRecorder()) {
                is AppResult.Success -> AppResult.Success(active)
                is AppResult.Failure -> started
            }
        }

        val created = tracks.startTrack(ids.newId(), clock.nowUtcMillis())
        if (created !is AppResult.Success) return created
        return when (val started = controller.startRecorder()) {
            is AppResult.Success -> created
            is AppResult.Failure -> {
                tracks.deleteTrackIfEmpty(created.value.id)
                started
            }
        }
    }
}

/** Explicit user action: stop the service and close the active track. */
class StopTrackingUseCase @Inject constructor(
    private val tracks: TrackRepository,
    private val controller: TrackingController,
    private val settings: SettingsRepository,
    private val syncScheduler: SyncScheduler,
    private val clock: Clock,
) {
    suspend operator fun invoke(): AppResult<Track> {
        controller.stopRecorder()
        val active = tracks.getActiveTrack() ?: return AppResult.Failure(AppError.NotTracking)
        val result = tracks.finishTrack(active.id, clock.nowUtcMillis(), TrackStatus.COMPLETED)
        val current = settings.current()
        if (result is AppResult.Success && current.syncEnabled && current.autoSync) syncScheduler.requestSyncNow()
        return result
    }
}

sealed interface RecoveryOutcome {
    data object NothingToRecover : RecoveryOutcome
    data object AlreadyRunning : RecoveryOutcome
    data class Resumed(val track: Track) : RecoveryOutcome
    data class Interrupted(val track: Track, val reason: AppError?) : RecoveryOutcome
}

/**
 * Called when the app comes to the foreground. If the process died while recording (crash, OOM
 * kill, reboot), the track is still ACTIVE in the database but no service runs. A recent track is
 * resumed (the user started it and never stopped it); an old one, or one that cannot be resumed,
 * is closed as INTERRUPTED at its last recorded moment.
 */
class RecoverTrackingUseCase @Inject constructor(
    private val tracks: TrackRepository,
    private val controller: TrackingController,
    private val location: LocationRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(): RecoveryOutcome {
        val active = tracks.getActiveTrack() ?: return RecoveryOutcome.NothingToRecover
        if (controller.isRecorderRunning) return RecoveryOutcome.AlreadyRunning
        val lastActivity = active.lastPointAtUtc ?: active.startedAtUtc
        val tooOld = clock.nowUtcMillis() - lastActivity > MAX_RESUME_GAP_MILLIS
        val reason: AppError? = when {
            tooOld -> null
            !location.permissionState().anyLocation -> AppError.LocationPermissionDenied
            else -> (controller.startRecorder() as? AppResult.Failure)?.error
        }
        if (!tooOld && reason == null) return RecoveryOutcome.Resumed(active)
        val closed = tracks.finishTrack(active.id, lastActivity, TrackStatus.INTERRUPTED)
        return RecoveryOutcome.Interrupted((closed as? AppResult.Success)?.value ?: active, reason)
    }

    companion object {
        const val MAX_RESUME_GAP_MILLIS = 6 * 60 * 60 * 1000L
    }
}

class ObserveTrackingStateUseCase @Inject constructor(private val controller: TrackingController) {
    operator fun invoke(): StateFlow<TrackingState> = controller.state
}
