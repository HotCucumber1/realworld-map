package com.worldexplorer.domain.tracking

import com.worldexplorer.domain.fakes.FakeClock
import com.worldexplorer.domain.fakes.FakeExplorationRepository
import com.worldexplorer.domain.fakes.FakeTrackRepository
import com.worldexplorer.domain.fakes.SequentialIds
import com.worldexplorer.domain.fakes.TestGeo
import com.worldexplorer.domain.filter.RejectReason
import com.worldexplorer.domain.geo.ExplorationGrid
import com.worldexplorer.domain.model.TrackStatus
import com.worldexplorer.domain.result.AppError
import com.worldexplorer.domain.usecase.RecordLocationUseCase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackRecorderTest {
    private val clock = FakeClock()
    private val exploration = FakeExplorationRepository()
    private val tracks = FakeTrackRepository(exploration)
    private val recorder = TrackRecorder.Factory(tracks, RecordLocationUseCase(tracks, clock, SequentialIds("p")), clock).create()

    @Test
    fun `accepted samples are stored with distance, points and explored cells`() = runTest {
        tracks.startTrack("t", clock.now)
        recorder.attach("t")
        var lat = TestGeo.BASE_LAT
        repeat(5) {
            clock.now += 4_000
            recorder.onSample(TestGeo.sample(lat = lat, t = clock.now), 20)
            lat = TestGeo.north(lat, 6.0)
        }
        val track = tracks.getTrack("t")!!
        assertEquals(5, track.pointCount)
        assertEquals(24.0, track.distanceMeters, 0.05)
        val expected = (0 until 5).flatMap { ExplorationGrid.cellsWithinRadius(TestGeo.north(TestGeo.BASE_LAT, it * 6.0), TestGeo.BASE_LON, 20.0) }.toSet()
        assertEquals(expected, exploration.cells)
        assertTrue(tracks.points.value["t"]!!.all { it.exploreRadiusMeters == 20 })
    }

    @Test
    fun `revisiting explored cells adds no new cells`() = runTest {
        tracks.startTrack("t", clock.now)
        recorder.attach("t")
        clock.now += 4_000
        val first = recorder.onSample(TestGeo.sample(t = clock.now), 20) as RecordOutcome.Recorded
        val count = exploration.cells.size
        clock.now += 40_000
        val second = recorder.onSample(TestGeo.sample(t = clock.now), 20) as RecordOutcome.Recorded
        assertTrue(first.newCells > 0)
        assertEquals(0, second.newCells)
        assertEquals(count, exploration.cells.size)
        assertEquals(2, tracks.getTrack("t")!!.pointCount)
    }

    @Test
    fun `rejected samples are counted for quality statistics`() = runTest {
        tracks.startTrack("t", clock.now)
        recorder.attach("t")
        clock.now += 1_000
        val outcome = recorder.onSample(TestGeo.sample(t = clock.now, accuracy = 500f), 20)
        assertEquals(RecordOutcome.Rejected(RejectReason.LOW_ACCURACY), outcome)
        recorder.flushRejected()
        assertEquals(1, tracks.getTrack("t")!!.rejectedPointCount)
    }

    @Test
    fun `attach restores the anchor so a replayed fix is not stored twice`() = runTest {
        tracks.startTrack("t", clock.now)
        recorder.attach("t")
        clock.now += 4_000
        val s = TestGeo.sample(t = clock.now)
        recorder.onSample(s, 20)
        val restarted = TrackRecorder.Factory(tracks, RecordLocationUseCase(tracks, clock, SequentialIds("q")), clock).create()
        restarted.attach("t")
        assertTrue(restarted.onSample(s, 20) is RecordOutcome.Rejected)
        assertEquals(1, tracks.getTrack("t")!!.pointCount)
    }

    @Test
    fun `samples after the track was stopped are not stored`() = runTest {
        tracks.startTrack("t", clock.now)
        recorder.attach("t")
        tracks.finishTrack("t", clock.now, TrackStatus.COMPLETED)
        clock.now += 4_000
        assertEquals(RecordOutcome.Failed(AppError.NotTracking), recorder.onSample(TestGeo.sample(t = clock.now), 20))
    }
}
