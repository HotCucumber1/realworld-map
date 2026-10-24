package com.worldexplorer.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.worldexplorer.domain.model.LocationSample
import com.worldexplorer.domain.model.LocationSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Platform source of raw fixes. Callers must hold a location permission. */
interface LocationClient {
    fun updates(intervalMillis: Long): Flow<LocationSample>
}

/** Google Play services Fused Location Provider: best accuracy/battery trade-off where available. */
class FusedLocationClient(context: Context) : LocationClient {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    override fun updates(intervalMillis: Long): Flow<LocationSample> = callbackFlow {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis)
            .setMinUpdateIntervalMillis(intervalMillis / 2)
            .setWaitForAccurateLocation(false)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { trySend(it.toSample()) }
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener { e -> close(e) }
        awaitClose { client.removeLocationUpdates(callback) }
    }
}

/** Fallback for devices without Google Play services: raw GPS via LocationManager. */
class PlatformLocationClient(context: Context) : LocationClient {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission")
    override fun updates(intervalMillis: Long): Flow<LocationSample> = callbackFlow {
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(location.toSample())
            }

            override fun onProviderDisabled(provider: String) = Unit
            override fun onProviderEnabled(provider: String) = Unit
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { manager.allProviders.contains(it) }
        if (providers.isEmpty()) {
            close(IllegalStateException("no location provider"))
        } else {
            providers.forEach { manager.requestLocationUpdates(it, intervalMillis, 0f, listener, Looper.getMainLooper()) }
        }
        awaitClose { manager.removeUpdates(listener) }
    }
}

object LocationClientFactory {
    fun create(context: Context): LocationClient {
        val gms = runCatching {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        }.getOrDefault(false)
        Log.i("WorldExplorer", "location client: ${if (gms) "fused" else "platform"}")
        return if (gms) FusedLocationClient(context) else PlatformLocationClient(context)
    }
}

fun Location.toSample() = LocationSample(
    latitude = latitude,
    longitude = longitude,
    timestampUtcMillis = time,
    accuracyMeters = if (hasAccuracy()) accuracy else null,
    altitudeMeters = if (hasAltitude()) altitude else null,
    speedMetersPerSecond = if (hasSpeed()) speed else null,
    bearingDegrees = if (hasBearing()) bearing else null,
    source = when (provider) {
        LocationManager.GPS_PROVIDER -> LocationSource.GPS
        LocationManager.NETWORK_PROVIDER -> LocationSource.NETWORK
        LocationManager.PASSIVE_PROVIDER -> LocationSource.PASSIVE
        "fused" -> LocationSource.FUSED
        else -> LocationSource.UNKNOWN
    },
)
