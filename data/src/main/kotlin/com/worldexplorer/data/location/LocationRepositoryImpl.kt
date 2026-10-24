package com.worldexplorer.data.location

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.worldexplorer.domain.model.CurrentLocation
import com.worldexplorer.domain.model.GeoPoint
import com.worldexplorer.domain.model.GpsStatus
import com.worldexplorer.domain.model.LocationPermissionState
import com.worldexplorer.domain.model.LocationSample
import com.worldexplorer.domain.repository.ConnectivityRepository
import com.worldexplorer.domain.repository.LocationRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocationRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: LocationClient,
) : LocationRepository {
    private val last = MutableStateFlow<CurrentLocation?>(null)
    override val lastLocation: StateFlow<CurrentLocation?> = last.asStateFlow()

    override fun locationUpdates(intervalMillis: Long): Flow<LocationSample> {
        if (!permissionState().anyLocation) throw SecurityException("location permission not granted")
        return client.updates(intervalMillis).onEach(::publish)
    }

    private fun publish(sample: LocationSample) {
        val current = last.value
        if (current == null || sample.timestampUtcMillis >= current.timestampUtcMillis) {
            last.value = CurrentLocation(GeoPoint(sample.latitude, sample.longitude), sample.accuracyMeters, sample.timestampUtcMillis)
        }
    }

    override fun observeGpsStatus(): Flow<GpsStatus> = callbackFlow {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        fun current() = GpsStatus(LocationManagerCompat.isLocationEnabled(manager))
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                trySend(current())
            }
        }
        val filter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) addAction(LocationManager.ACTION_GNSS_CAPABILITIES_CHANGED)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        trySend(current())
        awaitClose { context.unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    override fun permissionState(): LocationPermissionState {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        val background = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else fine || coarse
        val notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
        return LocationPermissionState(fine, coarse, background, notifications)
    }
}

@Singleton
class ConnectivityRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : ConnectivityRepository {
    private val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun isOnline(): Boolean {
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun observeOnline(): Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(isOnline()) }
            override fun onLost(network: Network) { trySend(isOnline()) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { trySend(isOnline()) }
        }
        trySend(isOnline())
        manager.registerDefaultNetworkCallback(callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
