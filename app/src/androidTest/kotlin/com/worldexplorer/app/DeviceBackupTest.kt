package com.worldexplorer.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.worldexplorer.data.backup.AppBuildInfo
import com.worldexplorer.data.backup.BackupImporter
import com.worldexplorer.data.backup.BackupReader
import com.worldexplorer.data.backup.BackupRepositoryImpl
import com.worldexplorer.data.backup.BackupWriter
import com.worldexplorer.data.backup.ContentResolverDocumentStreams
import com.worldexplorer.data.database.ExplorationStore
import com.worldexplorer.data.database.WorldExplorerDatabase
import com.worldexplorer.data.repository.TrackRepositoryImpl
import com.worldexplorer.domain.AppDispatchers
import com.worldexplorer.domain.backup.ImportMode
import com.worldexplorer.domain.geo.ExplorationGrid
import com.worldexplorer.domain.geo.GeoMath
import com.worldexplorer.domain.model.AppSettings
import com.worldexplorer.domain.model.LocationSource
import com.worldexplorer.domain.model.TrackPoint
import com.worldexplorer.domain.model.TrackStatus
import com.worldexplorer.domain.repository.SettingsRepository
import com.worldexplorer.domain.result.AppResult
import com.worldexplorer.domain.tracking.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Export → import round-trip on a real device: real SQLite, real ZIP and file I/O. */
@RunWith(AndroidJUnit4::class)
class DeviceBackupTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val source = Room.inMemoryDatabaseBuilder(context, WorldExplorerDatabase::class.java).build()
    private val target = Room.inMemoryDatabaseBuilder(context, WorldExplorerDatabase::class.java).build()
    private val clock = Clock { 1_700_000_000_000L }
    private val file = File(context.cacheDir, "device-backup-test.zip")

    private class Settings : SettingsRepository {
        val state = MutableStateFlow(AppSettings())
        override val settings = state
        override suspend fun current() = state.value
        override suspend fun update(transform: (AppSettings) -> AppSettings) { state.value = transform(state.value) }
    }

    private fun repo(db: WorldExplorerDatabase) = BackupRepositoryImpl(
        context, db, ContentResolverDocumentStreams(context), BackupWriter(db), BackupReader(), BackupImporter(db, BackupReader(), ExplorationStore(db)),
        Settings(), clock, AppBuildInfo("test"), AppDispatchers(Dispatchers.IO, Dispatchers.Default),
    )

    @After fun tearDown() { source.close(); target.close(); file.delete() }

    @Test
    fun exportImportRoundTripAndIdempotentMerge() = runBlocking {
        val tracks = TrackRepositoryImpl(source, ExplorationStore(source))
        tracks.startTrack("t", 1_000)
        repeat(30) { i ->
            val lat = 10.0 + i * 0.00006
            val step = if (i == 0) 0.0 else GeoMath.haversineMeters(lat - 0.00006, 20.0, lat, 20.0)
            tracks.recordPoint(TrackPoint("p$i", "t", lat, 20.0, 2_000L + i * 4_000, 5f, null, null, null, LocationSource.GPS, 20), step, ExplorationGrid.cellsWithinRadius(lat, 20.0, 20.0), 2_000L + i * 4_000)
        }
        tracks.finishTrack("t", 200_000, TrackStatus.COMPLETED)

        assertTrue(repo(source).export(file.absolutePath) is AppResult.Success)
        val first = repo(target).import(file.absolutePath, ImportMode.MERGE) as AppResult.Success
        assertEquals(30L, first.value.pointsInserted)
        val second = repo(target).import(file.absolutePath, ImportMode.MERGE) as AppResult.Success
        assertEquals(0L, second.value.pointsInserted)

        assertEquals(source.cellDao().count(), target.cellDao().count())
        assertEquals(30, target.pointDao().countForTrack("t"))
        assertEquals(source.trackDao().getById("t")!!.distanceMeters, target.trackDao().getById("t")!!.distanceMeters, 0.01)
    }
}
