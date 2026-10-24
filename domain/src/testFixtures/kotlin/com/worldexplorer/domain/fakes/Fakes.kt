package com.worldexplorer.domain.fakes

import com.worldexplorer.domain.geo.CellKey
import com.worldexplorer.domain.geo.ExplorationGrid
import com.worldexplorer.domain.geo.GeoMath
import com.worldexplorer.domain.geo.WindowSpec
import com.worldexplorer.domain.geo.packCell
import com.worldexplorer.domain.model.AppSettings
import com.worldexplorer.domain.model.CurrentLocation
import com.worldexplorer.domain.model.ExplorationStats
import com.worldexplorer.domain.model.GpsQualityStats
import com.worldexplorer.domain.model.GpsStatus
import com.worldexplorer.domain.model.LocationPermissionState
import com.worldexplorer.domain.model.LocationSample
import com.worldexplorer.domain.model.Track
import com.worldexplorer.domain.model.TrackPoint
import com.worldexplorer.domain.model.TrackStatus
import com.worldexplorer.domain.model.TrackingState
import com.worldexplorer.domain.repository.ConnectivityRepository
import com.worldexplorer.domain.repository.ExplorationRepository
import com.worldexplorer.domain.repository.LocationRepository
import com.worldexplorer.domain.repository.RecordedPoint
import com.worldexplorer.domain.repository.SettingsRepository
import com.worldexplorer.domain.repository.SyncScheduler
import com.worldexplorer.domain.repository.TrackRepository
import com.worldexplorer.domain.repository.TrackingController
import com.worldexplorer.domain.result.AppError
import com.worldexplorer.domain.result.AppResult
import com.worldexplorer.domain.tracking.Clock
import com.worldexplorer.domain.tracking.IdGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

class FakeClock(var now: Long = 1_700_000_000_000L) : Clock {
    override fun nowUtcMillis(): Long = now
}

class SequentialIds(private val prefix: String = "id") : IdGenerator {
    private var n = 0
    override fun newId(): String = "$prefix-${++n}"
}

/** In-memory exploration store sharing the cell set with [FakeTrackRepository]. */
class FakeExplorationRepository : ExplorationRepository {
    val cells = LinkedHashSet<CellKey>()
    val version = MutableStateFlow(0L)
    var loadCalls = 0

    fun add(newCells: Collection<CellKey>): Int {
        val added = newCells.count { cells.add(it) }
        if (added > 0) version.value++
        return added
    }

    override fun observeStats(): Flow<ExplorationStats> = version.map {
        ExplorationStats(cells.sumOf { c -> ExplorationGrid.cellAreaSquareMeters(c.row) }, cells.size.toLong())
    }

    override fun observeVersion(): Flow<Long> = version

    override suspend fun loadWindow(spec: WindowSpec): LongArray {
        loadCalls++
        return cells.asSequence()
            .map { it.parent(spec.level) }
            .filter { it.row.toLong() in spec.firstRow..spec.lastRow && it.column.toLong() in spec.firstColumn..spec.lastColumn }
            .map { packCell(it.row.toLong(), it.column.toLong()) }
            .distinct().toList().toLongArray()
    }
}

class FakeTrackRepository(private val exploration: FakeExplorationRepository = FakeExplorationRepository()) : TrackRepository {
    val tracks = MutableStateFlow<Map<String, Track>>(emptyMap())
    val points = MutableStateFlow<Map<String, List<TrackPoint>>>(emptyMap())
    var mutationCount = 0

    override fun observeTracks() = tracks.map { it.values.sortedByDescending { t -> t.startedAtUtc } }
    override fun observeTrack(trackId: String) = tracks.map { it[trackId] }
    override fun observeActiveTrack() = tracks.map { it.values.firstOrNull { t -> t.status == TrackStatus.ACTIVE } }
    override fun observePoints(trackId: String) = points.map { it[trackId].orEmpty() }
    override suspend fun getActiveTrack() = tracks.value.values.firstOrNull { it.status == TrackStatus.ACTIVE }
    override suspend fun getTrack(trackId: String) = tracks.value[trackId]
    override suspend fun getLastPoint(trackId: String) = points.value[trackId]?.lastOrNull()
    override suspend fun getQualityStats(trackId: String) = GpsQualityStats(null, null, null, null)

    override suspend fun startTrack(trackId: String, startedAtUtc: Long): AppResult<Track> {
        if (getActiveTrack() != null) return AppResult.Failure(AppError.AlreadyTracking)
        val t = Track(trackId, startedAtUtc, null, TrackStatus.ACTIVE, 0.0, 0, 0, null, startedAtUtc, startedAtUtc)
        put(t)
        return AppResult.Success(t)
    }

    override suspend fun finishTrack(trackId: String, endedAtUtc: Long, status: TrackStatus): AppResult<Track> {
        val t = tracks.value[trackId] ?: return AppResult.Failure(AppError.Validation("no track"))
        if (t.status != TrackStatus.ACTIVE) return AppResult.Success(t)
        val closed = t.copy(endedAtUtc = endedAtUtc, status = status, updatedAtUtc = endedAtUtc)
        put(closed)
        return AppResult.Success(closed)
    }

    override suspend fun recordPoint(point: TrackPoint, distanceIncrementMeters: Double, cells: Collection<CellKey>, nowUtc: Long): AppResult<RecordedPoint> {
        val t = tracks.value[point.trackId]
        if (t == null || t.status != TrackStatus.ACTIVE) return AppResult.Failure(AppError.NotTracking)
        points.value = points.value + (point.trackId to points.value[point.trackId].orEmpty() + point)
        put(t.copy(distanceMeters = t.distanceMeters + distanceIncrementMeters, pointCount = t.pointCount + 1, lastPointAtUtc = point.timestampUtc, updatedAtUtc = nowUtc))
        return AppResult.Success(RecordedPoint(point, exploration.add(cells)))
    }

    override suspend fun addRejectedPoints(trackId: String, count: Int) {
        tracks.value[trackId]?.let { put(it.copy(rejectedPointCount = it.rejectedPointCount + count)) }
    }

    override suspend fun deleteTrackIfEmpty(trackId: String) {
        if (points.value[trackId].isNullOrEmpty()) { tracks.value = tracks.value - trackId; mutationCount++ }
    }

    fun put(t: Track) {
        tracks.value = tracks.value + (t.id to t)
        mutationCount++
    }
}

class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state
    override suspend fun current() = state.value
    override suspend fun update(transform: (AppSettings) -> AppSettings) { state.value = transform(state.value) }
}

class FakeLocationRepository(
    var permission: LocationPermissionState = LocationPermissionState(fine = true, coarse = true, background = false, notifications = true),
    providerEnabled: Boolean = true,
) : LocationRepository {
    val gps = MutableStateFlow(GpsStatus(providerEnabled))
    val last = MutableStateFlow<CurrentLocation?>(null)
    var updates: Flow<LocationSample> = emptyFlow()
    override fun locationUpdates(intervalMillis: Long): Flow<LocationSample> = updates
    override val lastLocation: StateFlow<CurrentLocation?> = last
    override fun observeGpsStatus(): Flow<GpsStatus> = gps
    override fun permissionState() = permission
}

class FakeTrackingController(var startResult: AppResult<Unit> = AppResult.Success(Unit)) : TrackingController {
    override val state = MutableStateFlow<TrackingState>(TrackingState.Idle)
    override var isRecorderRunning = false
    var startCalls = 0
    var stopCalls = 0
    override fun startRecorder(): AppResult<Unit> {
        startCalls++
        if (startResult is AppResult.Success) isRecorderRunning = true
        return startResult
    }
    override fun stopRecorder() { stopCalls++; isRecorderRunning = false }
}

class FakeSyncScheduler : SyncScheduler {
    var periodic: Boolean? = null
    var nowRequests = 0
    override fun schedulePeriodic(unmeteredOnly: Boolean) { periodic = true }
    override fun cancelPeriodic() { periodic = false }
    override fun requestSyncNow() { nowRequests++ }
}

class FakeConnectivity(var online: Boolean = true) : ConnectivityRepository {
    override fun observeOnline(): Flow<Boolean> = MutableStateFlow(online)
    override fun isOnline() = online
}

/** Synthetic test coordinates (no real user data): a point and helpers to walk east/north in metres. */
object TestGeo {
    const val BASE_LAT = 48.0
    const val BASE_LON = 11.0
    fun north(lat: Double, meters: Double) = lat + meters / GeoMath.METERS_PER_DEGREE_LATITUDE
    fun east(lat: Double, lon: Double, meters: Double) = lon + meters / GeoMath.metersPerDegreeLongitude(lat)
    fun sample(lat: Double = BASE_LAT, lon: Double = BASE_LON, t: Long, accuracy: Float? = 5f, speed: Float? = null) =
        LocationSample(lat, lon, t, accuracy, speedMetersPerSecond = speed)
}
