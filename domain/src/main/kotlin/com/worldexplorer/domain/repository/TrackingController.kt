package com.worldexplorer.domain.repository

import com.worldexplorer.domain.model.TrackingState
import com.worldexplorer.domain.result.AppResult
import kotlinx.coroutines.flow.StateFlow

/** Starts and stops the platform recording component (a foreground service on Android). */
interface TrackingController {
    val state: StateFlow<TrackingState>
    val isRecorderRunning: Boolean
    fun startRecorder(): AppResult<Unit>
    fun stopRecorder()
}
