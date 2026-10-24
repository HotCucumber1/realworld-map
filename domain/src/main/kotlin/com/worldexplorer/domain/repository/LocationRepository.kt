package com.worldexplorer.domain.repository

import com.worldexplorer.domain.model.CurrentLocation
import com.worldexplorer.domain.model.GpsStatus
import com.worldexplorer.domain.model.LocationPermissionState
import com.worldexplorer.domain.model.LocationSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface LocationRepository {
    /** Continuous location updates. Throws SecurityException downstream if permission is missing. */
    fun locationUpdates(intervalMillis: Long): Flow<LocationSample>

    /** Last position seen by any subscriber (screen or recording service). */
    val lastLocation: StateFlow<CurrentLocation?>

    fun observeGpsStatus(): Flow<GpsStatus>
    fun permissionState(): LocationPermissionState
}

interface ConnectivityRepository {
    fun observeOnline(): Flow<Boolean>
    fun isOnline(): Boolean
}
