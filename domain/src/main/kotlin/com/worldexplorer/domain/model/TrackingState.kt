package com.worldexplorer.domain.model

enum class GpsSignal {
    /** No fix has been received yet in this session. */
    WAITING,
    OK,
    /** No fix for a while although the provider is enabled. */
    LOST,
}

/** Runtime state of the recording service, as observed by the UI. */
sealed interface TrackingState {
    data object Idle : TrackingState
    data object Starting : TrackingState
    data class Recording(
        val trackId: String,
        val startedAtUtc: Long,
        val signal: GpsSignal,
        val lastFixAtUtc: Long?,
    ) : TrackingState
    data class Failed(val reason: String) : TrackingState
}

data class LocationPermissionState(
    val fine: Boolean,
    val coarse: Boolean,
    val background: Boolean,
    val notifications: Boolean,
) {
    val anyLocation: Boolean get() = fine || coarse
}

/** Status of the location providers (independent of permissions). */
data class GpsStatus(val providerEnabled: Boolean)
