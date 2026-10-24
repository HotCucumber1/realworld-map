package com.worldexplorer.domain.usecase

import app.cash.turbine.test
import com.worldexplorer.domain.AppDispatchers
import com.worldexplorer.domain.fakes.FakeConnectivity
import com.worldexplorer.domain.fakes.FakeExplorationRepository
import com.worldexplorer.domain.fakes.FakeSettingsRepository
import com.worldexplorer.domain.fakes.FakeSyncScheduler
import com.worldexplorer.domain.fakes.FakeTrackRepository
import com.worldexplorer.domain.fakes.FakeTrackingController
import com.worldexplorer.domain.fakes.TestGeo
import com.worldexplorer.domain.geo.ExplorationGrid
import com.worldexplorer.domain.geo.GeoBounds
import com.worldexplorer.domain.model.AppSettings
import com.worldexplorer.domain.model.MapMode
import com.worldexplorer.domain.repository.SyncRepository
import com.worldexplorer.domain.result.AppError
import com.worldexplorer.domain.result.AppResult
import com.worldexplorer.domain.backup.ImportMode
import com.worldexplorer.domain.repository.BackupRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExplorationAndSettingsUseCasesTest {
    private val exploration = FakeExplorationRepository()
    private val viewport = GeoBounds(TestGeo.BASE_LAT - 0.001, TestGeo.BASE_LON - 0.001, TestGeo.BASE_LAT + 0.001, TestGeo.BASE_LON + 0.001)

    @Test
    fun `explored area is computed for the visible window`() = runTest {
        exploration.add(ExplorationGrid.cellsWithinRadius(TestGeo.BASE_LAT, TestGeo.BASE_LON, 20.0))
        val d = StandardTestDispatcher(testScheduler)
        val geometry = CalculateExploredAreaUseCase(exploration, AppDispatchers(d, d))(viewport)
        assertEquals(0, geometry.level)
        assertEquals(1, geometry.explored.size)
        assertEquals(1, geometry.fog.first().holes.size)
    }

    @Test
    fun `territory far outside the viewport is not loaded into geometry`() = runTest {
        exploration.add(ExplorationGrid.cellsWithinRadius(TestGeo.BASE_LAT + 1.0, TestGeo.BASE_LON, 20.0))
        val d = StandardTestDispatcher(testScheduler)
        val geometry = CalculateExploredAreaUseCase(exploration, AppDispatchers(d, d))(viewport)
        assertTrue(geometry.isEmpty)
    }

    @Test
    fun `geometry is recomputed when territory changes and after the viewport settles`() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val calc = CalculateExploredAreaUseCase(exploration, AppDispatchers(d, d))
        val viewports = MutableStateFlow(viewport)
        ObserveExploredAreaUseCase(exploration, calc)(viewports).test {
            assertTrue(awaitItem().isEmpty)
            exploration.add(ExplorationGrid.cellsWithinRadius(TestGeo.BASE_LAT, TestGeo.BASE_LON, 20.0))
            assertEquals(1, awaitItem().explored.size)
            // Rapid pans are debounced into a single recomputation.
            val loadsBefore = exploration.loadCalls
            repeat(5) { i -> viewports.value = viewport.copy(west = viewport.west - i * 1e-5) }
            advanceTimeBy(ObserveExploredAreaUseCase.VIEWPORT_DEBOUNCE_MILLIS + 10)
            awaitItem()
            assertEquals(loadsBefore + 1, exploration.loadCalls)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `switching map mode changes settings only, never tracks or territory`() = runTest {
        val tracks = FakeTrackRepository(exploration)
        tracks.startTrack("t", 1L)
        exploration.add(ExplorationGrid.cellsWithinRadius(TestGeo.BASE_LAT, TestGeo.BASE_LON, 20.0))
        val cellsBefore = exploration.cells.toSet()
        val versionBefore = exploration.version.value
        val mutationsBefore = tracks.mutationCount
        val settings = FakeSettingsRepository()
        val switch = SwitchMapModeUseCase(settings)

        switch(MapMode.EXPLORER)
        assertEquals(MapMode.EXPLORER, settings.state.value.mapMode)
        switch.toggle()
        assertEquals(MapMode.FOG_OF_WAR, settings.state.value.mapMode)

        assertEquals(cellsBefore, exploration.cells)
        assertEquals(versionBefore, exploration.version.value)
        assertEquals(mutationsBefore, tracks.mutationCount)
    }

    @Test
    fun `invalid radius is sanitized and sync toggling schedules work`() = runTest {
        val settings = FakeSettingsRepository()
        val scheduler = FakeSyncScheduler()
        val update = UpdateSettingsUseCase(settings, scheduler)
        update { it.copy(exploreRadiusMeters = 37) }
        assertEquals(20, settings.state.value.exploreRadiusMeters)
        update { it.copy(exploreRadiusMeters = 50) }
        assertEquals(50, settings.state.value.exploreRadiusMeters)
        update { it.copy(syncEnabled = true) }
        assertEquals(true, scheduler.periodic)
        update { it.copy(syncEnabled = false) }
        assertEquals(false, scheduler.periodic)
    }

    @Test
    fun `sync is not attempted when disabled, unconfigured or offline`() = runTest {
        val repo = mockk<SyncRepository>()
        every { repo.isConfigured } returns true
        coEvery { repo.sync() } returns AppResult.Success(mockk())
        val settings = FakeSettingsRepository(AppSettings(syncEnabled = false))
        val connectivity = FakeConnectivity(online = true)
        val useCase = SyncDataUseCase(repo, settings, connectivity)

        assertTrue((useCase() as AppResult.Failure).error is AppError.Validation)
        settings.state.value = AppSettings(syncEnabled = true)
        connectivity.online = false
        assertEquals(AppResult.Failure(AppError.NoNetwork), useCase())
        every { repo.isConfigured } returns false
        assertEquals(AppResult.Failure(AppError.SyncNotConfigured), useCase())
        coVerify(exactly = 0) { repo.sync() }

        every { repo.isConfigured } returns true
        connectivity.online = true
        assertTrue(useCase() is AppResult.Success)
        coVerify(exactly = 1) { repo.sync() }
    }

    @Test
    fun `import is refused while recording`() = runTest {
        val backup = mockk<BackupRepository>()
        val controller = FakeTrackingController().apply { isRecorderRunning = true }
        assertEquals(AppResult.Failure(AppError.AlreadyTracking), ImportBackupUseCase(backup, controller)("uri", ImportMode.MERGE))
        coVerify(exactly = 0) { backup.import(any(), any()) }
        assertFalse(controller.startCalls > 0)
    }
}
