package com.worldexplorer.domain.filter

import com.worldexplorer.domain.geo.GeoMath
import com.worldexplorer.domain.model.LocationSample

/**
 * All thresholds of GPS filtering in one place. Documented in ARCHITECTURE.md ("GPS filtering").
 */
data class LocationFilterConfig(
    /** Fixes with a worse horizontal accuracy are discarded (urban canyons often give 20–40 m). */
    val maxAccuracyMeters: Float = 40f,
    /** Implied speed above this (≈300 km/h) between two fixes is treated as a jump. */
    val maxSpeedMetersPerSecond: Double = 85.0,
    /** Fixes closer in time than this to the previous accepted one are bursts and are dropped. */
    val minIntervalMillis: Long = 1_000,
    /**
     * A fix with exactly the same coordinates as the previous one is a cached replay unless this
     * much time has passed. Standing still therefore still records a point at least this often.
     */
    val identicalPositionKeepAliveMillis: Long = 30_000,
    /** Fixes stamped further in the future than this (device clock skew) are invalid. */
    val maxFutureSkewMillis: Long = 5 * 60_000,
    /**
     * After this many consecutive mutually consistent "jump" fixes the filter accepts that the user
     * really moved (e.g. left the metro), so one bad anchor point cannot block recording forever.
     */
    val relocationConfirmations: Int = 2,
)

enum class RejectReason {
    INVALID_COORDINATES,
    NULL_ISLAND,
    INVALID_TIMESTAMP,
    MISSING_ACCURACY,
    LOW_ACCURACY,
    DUPLICATE_TIMESTAMP,
    OUT_OF_ORDER,
    TOO_FREQUENT,
    DUPLICATE_POSITION,
    UNREALISTIC_JUMP,
}

sealed interface FilterDecision {
    data class Accepted(val sample: LocationSample, val distanceFromPreviousMeters: Double) : FilterDecision
    data class Rejected(val reason: RejectReason) : FilterDecision
}

/**
 * Stateful filter for one recording session. Not thread-safe: feed it from a single coroutine.
 */
class LocationFilter(private val config: LocationFilterConfig = LocationFilterConfig()) {
    var lastAccepted: LocationSample? = null
        private set
    private var jumpCandidate: LocationSample? = null
    private var jumpConfirmations = 0

    /** Restores the anchor after a restart (e.g. last persisted point of the active track). */
    fun reset(lastAccepted: LocationSample?) {
        this.lastAccepted = lastAccepted
        jumpCandidate = null
        jumpConfirmations = 0
    }

    fun evaluate(sample: LocationSample, nowUtcMillis: Long): FilterDecision {
        if (!GeoMath.isValidCoordinate(sample.latitude, sample.longitude)) return reject(RejectReason.INVALID_COORDINATES)
        if (sample.latitude == 0.0 && sample.longitude == 0.0) return reject(RejectReason.NULL_ISLAND)
        if (sample.timestampUtcMillis <= 0 || sample.timestampUtcMillis > nowUtcMillis + config.maxFutureSkewMillis) {
            return reject(RejectReason.INVALID_TIMESTAMP)
        }
        val accuracy = sample.accuracyMeters
        if (accuracy == null || accuracy.isNaN() || accuracy <= 0f) return reject(RejectReason.MISSING_ACCURACY)
        if (accuracy > config.maxAccuracyMeters) return reject(RejectReason.LOW_ACCURACY)

        val previous = lastAccepted ?: return accept(sample, 0.0)
        val dt = sample.timestampUtcMillis - previous.timestampUtcMillis
        if (dt == 0L) return reject(RejectReason.DUPLICATE_TIMESTAMP)
        if (dt < 0L) return reject(RejectReason.OUT_OF_ORDER)
        if (dt < config.minIntervalMillis) return reject(RejectReason.TOO_FREQUENT)
        if (sample.latitude == previous.latitude && sample.longitude == previous.longitude &&
            dt < config.identicalPositionKeepAliveMillis
        ) {
            return reject(RejectReason.DUPLICATE_POSITION)
        }

        val distance = GeoMath.haversineMeters(previous.latitude, previous.longitude, sample.latitude, sample.longitude)
        if (isJump(previous, sample, distance, dt)) {
            val candidate = jumpCandidate
            if (candidate != null && sample.timestampUtcMillis > candidate.timestampUtcMillis && isConsistent(candidate, sample)) {
                jumpConfirmations++
                if (jumpConfirmations >= config.relocationConfirmations) return accept(sample, distance)
            } else {
                jumpConfirmations = 0
            }
            jumpCandidate = sample
            return FilterDecision.Rejected(RejectReason.UNREALISTIC_JUMP)
        }
        return accept(sample, distance)
    }

    private fun isJump(a: LocationSample, b: LocationSample, distance: Double, dtMillis: Long): Boolean {
        val tolerance = (a.accuracyMeters ?: 0f) + (b.accuracyMeters ?: 0f)
        if (distance <= tolerance) return false
        return distance / (dtMillis / 1000.0) > config.maxSpeedMetersPerSecond
    }

    private fun isConsistent(a: LocationSample, b: LocationSample): Boolean {
        val dt = b.timestampUtcMillis - a.timestampUtcMillis
        if (dt <= 0) return false
        val d = GeoMath.haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
        return !isJump(a, b, d, dt)
    }

    private fun accept(sample: LocationSample, distance: Double): FilterDecision {
        lastAccepted = sample
        jumpCandidate = null
        jumpConfirmations = 0
        return FilterDecision.Accepted(sample, distance)
    }

    private fun reject(reason: RejectReason) = FilterDecision.Rejected(reason)
}
