package com.worldexplorer.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.worldexplorer.data.database.ExplorationStore
import com.worldexplorer.data.database.WorldExplorerDatabase
import com.worldexplorer.data.repository.TrackRepositoryImpl
import com.worldexplorer.domain.geo.ExplorationGrid
import com.worldexplorer.domain.geo.GeoMath
import com.worldexplorer.domain.model.AppSettings
import com.worldexplorer.domain.model.LocationSource
import com.worldexplorer.domain.model.TrackPoint
import com.worldexplorer.domain.model.TrackStatus
import com.worldexplorer.domain.repository.SettingsRepository
import com.worldexplorer.domain.result.AppResult
import com.worldexplorer.domain.tracking.Clock
import kotlinx.coroutines.flow.MutableStateFlow

fun context(): Context = ApplicationProvider.getApplicationContext()

fun inMemoryDb(): WorldExplorerDatabase =
    Room.inMemoryDatabaseBuilder(context(), WorldExplorerDatabase::class.java).allowMainThreadQueries().build()

class MutableClock(var now: Long = 1_700_000_000_000L) : Clock {
    override fun nowUtcMillis() = now
}

class TestSettings(initial: AppSettings = AppSettings()) : SettingsRepository {
    val state = MutableStateFlow(initial)
    override val settings = state
    override suspend fun current() = state.value
    override suspend fun update(transform: (AppSettings) -> AppSettings) { state.value = transform(state.value) }
}

/** Records a synthetic walk (no real user coordinates) through the real repository. */
class Walker(private val db: WorldExplorerDatabase, private val clock: MutableClock) {
    val repo = TrackRepositoryImpl(db, ExplorationStore(db))
    private var n = 0

    suspend fun walk(trackId: String, startLat: Double, startLon: Double, points: Int, stepMeters: Double = 7.0, radius: Int = 20, finish: Boolean = true) {
        repo.startTrack(trackId, clock.now)
        var lat = startLat
        var prev: Pair<Double, Double>? = null
        repeat(points) {
            clock.now += 4_000
            val p = TrackPoint("$trackId-p${n++}", trackId, lat, startLon, clock.now, 5f, 100.0, 1.4f, 0f, LocationSource.GPS, radius)
            val d = prev?.let { GeoMath.haversineMeters(it.first, it.second, lat, startLon) } ?: 0.0
            val r = repo.recordPoint(p, d, ExplorationGrid.cellsWithinRadius(lat, startLon, radius.toDouble()), clock.now)
            check(r is AppResult.Success) { "record failed: $r" }
            prev = lat to startLon
            lat += stepMeters / GeoMath.METERS_PER_DEGREE_LATITUDE
        }
        if (finish) repo.finishTrack(trackId, clock.now, TrackStatus.COMPLETED)
    }
}
