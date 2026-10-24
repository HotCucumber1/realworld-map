package com.worldexplorer.data.sync

import com.worldexplorer.data.MutableClock
import com.worldexplorer.data.Walker
import com.worldexplorer.data.database.ExplorationStore
import com.worldexplorer.data.database.SyncState
import com.worldexplorer.data.database.WorldExplorerDatabase
import com.worldexplorer.data.inMemoryDb
import com.worldexplorer.domain.model.TrackStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncEngineTest {
    private val clock = MutableClock()
    private val cloud = FakeCloud()
    private val phoneA = inMemoryDb()
    private val phoneB = inMemoryDb()
    private val user = "user-1"

    @After fun close() { phoneA.close(); phoneB.close() }

    private fun engine(db: WorldExplorerDatabase) = SyncEngine(db, ExplorationStore(db), clock)
    private suspend fun sync(db: WorldExplorerDatabase, userId: String = user) = engine(db).run(userId, cloud.backendFor(userId))

    private suspend fun dump(db: WorldExplorerDatabase) = Triple(
        db.trackDao().getAll().map { it.copy(syncState = 0) }.sortedBy { it.id },
        db.trackDao().getAll().flatMap { db.pointDao().getForTrack(it.id) }.map { it.copy(createdAt = 0, syncState = 0) }.sortedBy { it.id },
        db.cellDao().pageAfter(-1, -1, Int.MAX_VALUE).map { it.copy(syncState = 0) },
    )

    @Test
    fun `data recorded on one phone appears on another`() = runTest {
        Walker(phoneA, clock).walk("walk", 48.0, 11.0, points = 15)
        val pushed = sync(phoneA)
        assertEquals(1, pushed.pushedTracks)
        assertEquals(15L, pushed.pushedPoints)
        assertTrue(pushed.pushedCells > 0)

        val pulled = sync(phoneB)
        assertEquals(1, pulled.pulledTracks)
        assertEquals(15L, pulled.pulledPoints)
        assertEquals(dump(phoneA), dump(phoneB))
        assertEquals(phoneA.statsDao().get()!!.cellCount, phoneB.statsDao().get()!!.cellCount)
    }

    @Test
    fun `repeated sync transfers nothing and creates no duplicates`() = runTest {
        Walker(phoneA, clock).walk("walk", 48.0, 11.0, points = 10)
        sync(phoneA); sync(phoneB)
        val requestsBefore = cloud.requests
        val again = sync(phoneA)
        assertEquals(0, again.pushedTracks); assertEquals(0L, again.pushedPoints); assertEquals(0L, again.pulledPoints)
        // Incremental: only the empty pull pages were requested, nothing re-uploaded.
        assertEquals(3, cloud.requests - requestsBefore)
        sync(phoneB)
        assertEquals(10, cloud.points.size)
        assertEquals(10L, phoneB.pointDao().count())
    }

    @Test
    fun `both phones converge to the union of their territory`() = runTest {
        Walker(phoneA, clock).walk("a", 48.0, 11.0, points = 6)
        Walker(phoneB, clock).walk("b", 48.0005, 11.0, points = 6) // overlapping street
        sync(phoneA); sync(phoneB); sync(phoneA)
        assertEquals(dump(phoneA), dump(phoneB))
        assertEquals(2, phoneA.trackDao().count())
        assertEquals(phoneA.cellDao().count(), cloud.cells.size.toLong())
        // Overlapping cells agree on the earliest visit everywhere.
        val earliest = cloud.cells.values.associate { (it.row to it.column) to it.firstVisitedAt }
        phoneB.cellDao().pageAfter(-1, -1, Int.MAX_VALUE).forEach { assertEquals(earliest[it.row to it.column], it.firstVisitedAt) }
    }

    @Test
    fun `active track is uploaded only after recording stops`() = runTest {
        val w = Walker(phoneA, clock)
        w.walk("live", 48.0, 11.0, points = 4, finish = false)
        val first = sync(phoneA)
        assertEquals(0, first.pushedTracks)
        assertEquals(0L, first.pushedPoints)
        assertTrue("territory is uploaded right away", first.pushedCells > 0)
        w.repo.finishTrack("live", clock.now, TrackStatus.COMPLETED)
        val second = sync(phoneA)
        assertEquals(1, second.pushedTracks)
        assertEquals(4L, second.pushedPoints)
    }

    @Test
    fun `conflicting track edits resolve to the later update on every phone`() = runTest {
        Walker(phoneA, clock).walk("t", 48.0, 11.0, points = 3)
        sync(phoneA); sync(phoneB)
        val a = phoneA.trackDao().getById("t")!!
        val b = phoneB.trackDao().getById("t")!!
        phoneA.trackDao().update(a.copy(status = TrackStatus.INTERRUPTED.name, updatedAt = a.updatedAt + 10, syncState = SyncState.PENDING))
        phoneB.trackDao().update(b.copy(rejectedPointCount = 99, updatedAt = b.updatedAt + 20, syncState = SyncState.PENDING))
        sync(phoneA); sync(phoneB); sync(phoneA)
        val finalA = phoneA.trackDao().getById("t")!!
        assertEquals(99, finalA.rejectedPointCount)
        assertEquals(TrackStatus.COMPLETED.name, finalA.status)
        assertEquals(finalA.copy(syncState = 0), phoneB.trackDao().getById("t")!!.copy(syncState = 0))
    }

    @Test
    fun `interrupted sync resumes without duplicating anything`() = runTest {
        Walker(phoneA, clock).walk("t", 48.0, 11.0, points = 2_500, stepMeters = 2.0)
        cloud.failNext = 0
        // Let the track and the first point batch go through, then fail.
        val backend = cloud.backendFor(user)
        val flaky = object : SyncBackend by backend {
            var calls = 0
            override suspend fun pushPoints(points: List<RemotePoint>) {
                if (++calls == 2) throw SyncException(com.worldexplorer.domain.result.AppError.BackendUnavailable("drop"))
                backend.pushPoints(points)
            }
        }
        try {
            engine(phoneA).run(user, flaky)
            fail("expected failure")
        } catch (e: SyncException) {
            assertTrue(cloud.points.size in 1..1_000)
        }
        sync(phoneA)
        assertEquals(2_500, cloud.points.size)
        assertEquals(0, phoneA.syncDao().pendingPoints(10).size)
        sync(phoneB)
        assertEquals(2_500L, phoneB.pointDao().count())
    }

    @Test
    fun `accounts do not see each other's data`() = runTest {
        Walker(phoneA, clock).walk("mine", 48.0, 11.0, points = 3)
        sync(phoneA, "alice")
        val other = sync(phoneB, "bob")
        assertEquals(0, other.pulledTracks)
        assertEquals(0L, phoneB.pointDao().count())
    }

    @Test
    fun `points whose track row is not yet known get a placeholder that the real row replaces`() = runTest {
        Walker(phoneA, clock).walk("t", 48.0, 11.0, points = 3)
        sync(phoneA)
        // Simulate phone B having pulled tracks before the track existed: skip the track cursor.
        phoneB.syncMetadataDao().put(com.worldexplorer.data.database.SyncMetadataEntity(SyncCursors.tracks(user), Long.MAX_VALUE.toString()))
        sync(phoneB)
        assertEquals(0L, phoneB.trackDao().getById("t")!!.updatedAt)
        assertEquals(3, phoneB.trackDao().getById("t")!!.pointCount)
        phoneB.syncMetadataDao().put(com.worldexplorer.data.database.SyncMetadataEntity(SyncCursors.tracks(user), "0"))
        sync(phoneB)
        assertEquals(phoneA.trackDao().getById("t")!!.copy(syncState = 0), phoneB.trackDao().getById("t")!!.copy(syncState = 0))
    }
}
