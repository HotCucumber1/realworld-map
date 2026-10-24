package com.worldexplorer.app.tracking

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.worldexplorer.domain.model.TrackingState
import com.worldexplorer.domain.repository.TrackingController
import com.worldexplorer.domain.result.AppError
import com.worldexplorer.domain.result.AppResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts/stops [TrackingService] and holds its runtime state. Because the service runs in the app
 * process, this singleton is the single source of truth for "is the recorder alive": after a
 * process death it starts as not running, which is what recovery relies on.
 */
@Singleton
class TrackingServiceController @Inject constructor(
    @ApplicationContext private val context: Context,
) : TrackingController {
    private val mutableState = MutableStateFlow<TrackingState>(TrackingState.Idle)
    override val state: StateFlow<TrackingState> = mutableState.asStateFlow()

    @Volatile
    override var isRecorderRunning: Boolean = false
        private set

    override fun startRecorder(): AppResult<Unit> {
        if (isRecorderRunning) return AppResult.Success(Unit)
        return try {
            mutableState.value = TrackingState.Starting
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_START))
            AppResult.Success(Unit)
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (Android 12+) extends IllegalStateException.
            Log.e(TAG, "foreground service start refused", e)
            mutableState.value = TrackingState.Failed(e.javaClass.simpleName)
            AppResult.Failure(AppError.ServiceStartFailed(e.javaClass.simpleName))
        } catch (e: SecurityException) {
            Log.e(TAG, "foreground service start not permitted", e)
            mutableState.value = TrackingState.Failed("permission")
            AppResult.Failure(AppError.ServiceStartFailed("SecurityException"))
        }
    }

    override fun stopRecorder() {
        context.stopService(Intent(context, TrackingService::class.java))
    }

    internal fun onServiceStarted() {
        isRecorderRunning = true
    }

    internal fun update(state: TrackingState) {
        mutableState.value = state
    }

    internal fun onServiceStopped(failure: String? = null) {
        isRecorderRunning = false
        mutableState.value = failure?.let { TrackingState.Failed(it) } ?: TrackingState.Idle
    }

    private companion object {
        const val TAG = "WorldExplorer"
    }
}
