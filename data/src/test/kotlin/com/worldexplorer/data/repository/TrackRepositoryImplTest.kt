package com.worldexplorer.data.repository

import app.cash.turbine.test
import com.worldexplorer.data.MutableClock
import com.worldexplorer.data.Walker
import com.worldexplorer.data.database.SyncState
import com.worldexplorer.data.inMemoryDb
import com.worldexplorer.domain.geo.ExplorationGrid
import com.worldexplorer.domain.geo.ExploredGeometryBuilder
import com.worldexplorer.domain.geo.GeoBounds
import com.worldexplorer.domain.geo.WindowSpec
import com.worldexplorer.domain.model.LocationSource
import com.worldexplorer.domain.model.TrackPoint
import com.worldexplorer.domain.model.TrackStatus
import com.worldexplorer.domain.result.AppError
import com.worldexplorer.domain.result.AppResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrackRepositoryImplTest {
    private val db = inMemoryDb()
    private val clock = MutableClock()
    private val walker = Walker(db, clock)
    private val repo = walker.repo
    private val exploration = ExplorationRepositoryImpl(db)

    @After fun close() = db.close()

    @Test
    fun `track, points and statistics are stored and survive reading back`() = runTest {
        walker.walk("t1", 48.0, 11.0, points = 10, stepMeters = 7.0)
        val track = repo.getTrack("t1")!!
        assertEquals(TrackStatus.COMPLETED, track.status)
        assertEquals(10, track.pointCount)
        assertEquals(63.0, track.distanceMeters, 0.1)
        assertEquals(10, db.pointDao().countForTrack("t1"))
        assertEquals(track.lastPointAtUtc, repo.getLastPoint("t1")!!.timestampUtc)
        val quality = repo.getQualityStats("t1")
        assertEquals(5.0, quality.averageAccuracyMeters!!, 1e-6)
        assertEquals(1.0, quality.preciseShare!!, 1e-6)
    }

    @Test
    fun `history lists tracks newest first and reflects the active one`() = runTest {
        walker.walk("old", 48.0, 11.0, 3)
        clock.now += 60_000
        walker.walk("new", 48.1, 11.0, 3, finish = false)
        repo.observeTracks().test {
            val list = awaitItem()
            assertEquals(listOf("new", "old"), list.map { it.id })
            assertEquals(TrackStatus.ACTIVE, list.first().status)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("new", repo.observeActiveTrack().first()!!.id)
    }

    @Test
    fun `only one active track can exist`() = runTest {
        repo.startTrack("a", clock.now)
        assertEquals(AppResult.Failure(AppError.AlreadyTracking), repo.startTrack("b", clock.now))
        assertNull(repo.getTrack("b"))
    }

    @Test
    fun `explored cells match the union of point circles without duplicates`() = runTest {
        walker.walk("t", 48.0, 11.0, points = 6, stepMeters = 7.0, radius = 20)
        val expected = (0 until 6).flatMap { i ->
            ExplorationGrid.cellsWithinRadius(48.0 + i * 7.0 / com.worldexplorer.domain.geo.GeoMath.METERS_PER_DEGREE_LATITUDE, 11.0, 20.0)
        }.toSet()
        assertEquals(expected.size.toLong(), db.cellDao().count())
        val stats = exploration.observeStats().first()
        assertEquals(expected.size.toLong(), stats.cellCount)
        assertEquals(expected.sumOf { ExplorationGrid.cellAreaSquareMeters(it.row) }, stats.exploredAreaSquareMeters, 1e-6)
    }

    @Test
    fun `revisiting the same street adds points but no territory`() = runTest {
        walker.walk("first", 48.0, 11.0, points = 5)
        val cells = db.cellDao().count()
        val version = exploration.observeVersion().first()
        walker.walk("second", 48.0, 11.0, points = 5)
        assertEquals(cells, db.cellDao().count())
        assertEquals(version, exploration.observeVersion().first())
        assertEquals(10L, db.pointDao().count())
    }

    @Test
    fun `point for a stopped track is rejected and nothing is written`() = runTest {
        walker.walk("t", 48.0, 11.0, points = 2)
        val before = db.cellDao().count()
        val p = TrackPoint("late", "t", 48.5, 11.5, clock.now + 1, 5f, null, null, null, LocationSource.GPS, 20)
        val r = repo.recordPoint(p, 10.0, ExplorationGrid.cellsWithinRadius(48.5, 11.5, 20.0), clock.now)
        assertEquals(AppResult.Failure(AppError.NotTracking), r)
        assertEquals(before, db.cellDao().count())
        assertEquals(2, repo.getTrack("t")!!.pointCount)
    }

    @Test
    fun `failure inside recordPoint rolls back the whole transaction`() = runTest {
        walker.walk("t", 48.0, 11.0, points = 1, finish = false)
        val track = repo.getTrack("t")!!
        val cells = db.cellDao().count()
        val existingId = db.pointDao().getForTrack("t").single().id
        // Same point id again: the insert fails after the track check, so the track update and the
        // new cells far away must not be committed either.
        val dup = TrackPoint(existingId, "t", 49.0, 12.0, clock.now + 5_000, 5f, null, null, null, LocationSource.GPS, 20)
        val r = repo.recordPoint(dup, 99.0, ExplorationGrid.cellsWithinRadius(49.0, 12.0, 20.0), clock.now + 5_000)
        assertTrue("got $r", r is AppResult.Failure && r.error is AppError.Database)
        assertEquals(track, repo.getTrack("t"))
        assertEquals(cells, db.cellDao().count())
    }

    @Test
    fun `finishing marks the track pending for sync and is idempotent`() = runTest {
        walker.walk("t", 48.0, 11.0, points = 2, finish = false)
        val first = repo.finishTrack("t", clock.now, TrackStatus.COMPLETED) as AppResult.Success
        val again = repo.finishTrack("t", clock.now + 10_000, TrackStatus.INTERRUPTED) as AppResult.Success
        assertEquals(first.value, again.value)
        assertEquals(SyncState.PENDING, db.trackDao().getById("t")!!.syncState)
    }

    @Test
    fun `empty track can be deleted, a track with points cannot`() = runTest {
        repo.startTrack("empty", clock.now)
        repo.deleteTrackIfEmpty("empty")
        assertNull(repo.getTrack("empty"))
        walker.walk("full", 48.0, 11.0, 1)
        repo.deleteTrackIfEmpty("full")
        assertTrue(repo.getTrack("full") != null)
    }

    @Test
    fun `coarse summary levels agree with base cells`() = runTest {
        walker.walk("t", 48.0, 11.0, points = 30, stepMeters = 15.0)
        val bounds = GeoBounds(47.99, 10.99, 48.02, 11.01)
        for (level in listOf(0, 3, 4, 6, 8, 10)) {
            val spec = ExploredGeometryBuilder.windowsFor(bounds, level).single()
            val fromRepo = exploration.loadWindow(spec).toSet()
            val base = exploration.loadWindow(WindowSpec(0, spec.firstRow shl level, ((spec.lastRow + 1) shl level) - 1, spec.firstColumn shl level, ((spec.lastColumn + 1) shl level) - 1))
            val expected = base.map { com.worldexplorer.domain.geo.packCell(com.worldexplorer.domain.geo.unpackRow(it) shr level, com.worldexplorer.domain.geo.unpackColumn(it) shr level) }.toSet()
            assertEquals("level $level", expected, fromRepo)
        }
    }

    @Test
    fun `rejected points counter accumulates`() = runTest {
        repo.startTrack("t", clock.now)
        repo.addRejectedPoints("t", 3)
        repo.addRejectedPoints("t", 2)
        assertEquals(5, repo.getTrack("t")!!.rejectedPointCount)
    }
}
