package com.worldexplorer.domain.usecase

import com.worldexplorer.domain.fakes.FakeClock
import com.worldexplorer.domain.fakes.FakeLocationRepository
import com.worldexplorer.domain.fakes.FakeSettingsRepository
import com.worldexplorer.domain.fakes.FakeSyncScheduler
import com.worldexplorer.domain.fakes.FakeTrackRepository
import com.worldexplorer.domain.fakes.FakeTrackingController
import com.worldexplorer.domain.fakes.SequentialIds
import com.worldexplorer.domain.model.AppSettings
import com.worldexplorer.domain.model.LocationPermissionState
import com.worldexplorer.domain.model.TrackStatus
import com.worldexplorer.domain.result.AppError
import com.worldexplorer.domain.result.AppResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingUseCasesTest {
    private val clock = FakeClock()
    private val tracks = FakeTrackRepository()
    private val controller = FakeTrackingController()
    private val location = FakeLocationRepository()
    private val settings = FakeSettingsRepository()
    private val scheduler = FakeSyncScheduler()
    private val start = StartTrackingUseCase(tracks, controller, location, clock, SequentialIds("track"))
    private val stop = StopTrackingUseCase(tracks, controller, settings, scheduler, clock)
    private val recover = RecoverTrackingUseCase(tracks, controller, location, clock)

    @Test
    fun `start creates an active track and starts the recorder`() = runTest {
        val result = start()
        assertTrue(result is AppResult.Success)
        assertEquals(TrackStatus.ACTIVE, tracks.getActiveTrack()?.status)
        assertEquals(1, controller.startCalls)
    }

    @Test
    fun `start without permission fails and creates nothing`() = runTest {
        location.permission = LocationPermissionState(false, false, false, false)
        assertEquals(AppResult.Failure(AppError.LocationPermissionDenied), start())
        assertTrue(tracks.tracks.value.isEmpty())
        assertEquals(0, controller.startCalls)
    }

    @Test
    fun `start with location disabled fails`() = runTest {
        location.gps.value = location.gps.value.copy(providerEnabled = false)
        assertEquals(AppResult.Failure(AppError.LocationDisabled), start())
    }

    @Test
    fun `second start while recording is refused`() = runTest {
        start()
        assertEquals(AppResult.Failure(AppError.AlreadyTracking), start())
        assertEquals(1, tracks.tracks.value.size)
    }

    @Test
    fun `failed service start removes the empty track`() = runTest {
        controller.startResult = AppResult.Failure(AppError.ServiceStartFailed("boom"))
        assertTrue(start() is AppResult.Failure)
        assertNull(tracks.getActiveTrack())
        assertTrue(tracks.tracks.value.isEmpty())
    }

    @Test
    fun `start after process death continues the existing active track`() = runTest {
        tracks.startTrack("orphan", clock.now)
        val result = start() as AppResult.Success
        assertEquals("orphan", result.value.id)
        assertEquals(1, tracks.tracks.value.size)
    }

    @Test
    fun `stop completes the track and stops the recorder`() = runTest {
        start()
        clock.now += 60_000
        val stopped = stop() as AppResult.Success
        assertEquals(TrackStatus.COMPLETED, stopped.value.status)
        assertEquals(clock.now, stopped.value.endedAtUtc)
        assertEquals(1, controller.stopCalls)
        assertEquals(0, scheduler.nowRequests)
    }

    @Test
    fun `stop requests sync when auto sync is on`() = runTest {
        settings.state.value = AppSettings(syncEnabled = true, autoSync = true)
        start(); stop()
        assertEquals(1, scheduler.nowRequests)
    }

    @Test
    fun `stop without active track reports not tracking`() = runTest {
        assertEquals(AppResult.Failure(AppError.NotTracking), stop())
    }

    @Test
    fun `recovery resumes a recent orphaned track`() = runTest {
        tracks.startTrack("t", clock.now)
        clock.now += 10 * 60_000
        assertTrue(recover() is RecoveryOutcome.Resumed)
        assertTrue(controller.isRecorderRunning)
    }

    @Test
    fun `recovery closes an old orphaned track as interrupted at its last activity`() = runTest {
        tracks.startTrack("t", clock.now)
        val startedAt = clock.now
        clock.now += RecoverTrackingUseCase.MAX_RESUME_GAP_MILLIS + 1
        val outcome = recover() as RecoveryOutcome.Interrupted
        assertEquals(TrackStatus.INTERRUPTED, outcome.track.status)
        assertEquals(startedAt, outcome.track.endedAtUtc)
        assertEquals(0, controller.startCalls)
    }

    @Test
    fun `recovery without permission interrupts the track with a reason`() = runTest {
        tracks.startTrack("t", clock.now)
        location.permission = LocationPermissionState(false, false, false, false)
        val outcome = recover() as RecoveryOutcome.Interrupted
        assertEquals(AppError.LocationPermissionDenied, outcome.reason)
    }

    @Test
    fun `recovery does nothing when the recorder runs or no track is active`() = runTest {
        assertEquals(RecoveryOutcome.NothingToRecover, recover())
        start()
        assertEquals(RecoveryOutcome.AlreadyRunning, recover())
    }
}
