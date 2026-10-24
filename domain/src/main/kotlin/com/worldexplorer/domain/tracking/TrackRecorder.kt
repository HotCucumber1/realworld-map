package com.worldexplorer.domain.tracking

import com.worldexplorer.domain.filter.FilterDecision
import com.worldexplorer.domain.filter.LocationFilter
import com.worldexplorer.domain.filter.LocationFilterConfig
import com.worldexplorer.domain.filter.RejectReason
import com.worldexplorer.domain.model.LocationSample
import com.worldexplorer.domain.model.TrackingProfile
import com.worldexplorer.domain.repository.TrackRepository
import com.worldexplorer.domain.result.AppError
import com.worldexplorer.domain.result.AppResult
import com.worldexplorer.domain.usecase.RecordLocationUseCase
import javax.inject.Inject

sealed interface RecordOutcome {
    data class Recorded(val sample: LocationSample, val newCells: Int) : RecordOutcome
    data class Rejected(val reason: RejectReason) : RecordOutcome
    data class Failed(val error: AppError) : RecordOutcome
}

/**
 * One recording session: filters raw samples, persists accepted ones and decides the sampling
 * interval. Platform-independent; the foreground service only feeds it samples.
 */
class TrackRecorder(
    private val tracks: TrackRepository,
    private val recordLocation: RecordLocationUseCase,
    private val clock: Clock,
    filterConfig: LocationFilterConfig,
) {
    private val filter = LocationFilter(filterConfig)
    private val recent = ArrayDeque<LocationSample>()
    private var pendingRejected = 0
    var trackId: String? = null
        private set

    /** Binds the session to a track and restores the filter anchor from its last stored point. */
    suspend fun attach(trackId: String) {
        this.trackId = trackId
        val last = tracks.getLastPoint(trackId)
        filter.reset(
            last?.let {
                LocationSample(it.latitude, it.longitude, it.timestampUtc, it.accuracyMeters, it.altitudeMeters, it.speedMetersPerSecond, it.bearingDegrees, it.source)
            },
        )
        recent.clear()
    }

    suspend fun onSample(sample: LocationSample, exploreRadiusMeters: Int): RecordOutcome {
        val id = trackId ?: return RecordOutcome.Failed(AppError.NotTracking)
        return when (val decision = filter.evaluate(sample, clock.nowUtcMillis())) {
            is FilterDecision.Rejected -> {
                pendingRejected++
                if (pendingRejected >= REJECTED_FLUSH_THRESHOLD) flushRejected()
                RecordOutcome.Rejected(decision.reason)
            }
            is FilterDecision.Accepted -> {
                remember(sample)
                when (val r = recordLocation(id, sample, decision.distanceFromPreviousMeters, exploreRadiusMeters)) {
                    is AppResult.Success -> RecordOutcome.Recorded(sample, r.value.newCells)
                    is AppResult.Failure -> RecordOutcome.Failed(r.error)
                }
            }
        }
    }

    fun desiredIntervalMillis(profile: TrackingProfile): Long =
        LocationRequestPolicy.intervalMillis(profile, LocationRequestPolicy.isStationary(recent.toList(), clock.nowUtcMillis()))

    /** Persists the count of rejected fixes (GPS quality statistics). */
    suspend fun flushRejected() {
        val id = trackId ?: return
        if (pendingRejected > 0) {
            tracks.addRejectedPoints(id, pendingRejected)
            pendingRejected = 0
        }
    }

    private fun remember(sample: LocationSample) {
        recent.addLast(sample)
        while (recent.size > RECENT_CAPACITY) recent.removeFirst()
    }

    class Factory @Inject constructor(
        private val tracks: TrackRepository,
        private val recordLocation: RecordLocationUseCase,
        private val clock: Clock,
    ) {
        fun create(config: LocationFilterConfig = LocationFilterConfig()) = TrackRecorder(tracks, recordLocation, clock, config)
    }

    private companion object {
        const val RECENT_CAPACITY = 20
        const val REJECTED_FLUSH_THRESHOLD = 10
    }
}
