package com.worldexplorer.app.feature.map

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.worldexplorer.app.MainDispatcherRule
import com.worldexplorer.app.R
import com.worldexplorer.app.ui.common.UiText
import com.worldexplorer.domain.AppDispatchers
import com.worldexplorer.domain.fakes.FakeClock
import com.worldexplorer.domain.fakes.FakeConnectivity
import com.worldexplorer.domain.fakes.FakeExplorationRepository
import com.worldexplorer.domain.fakes.FakeLocationRepository
import com.worldexplorer.domain.fakes.FakeSettingsRepository
import com.worldexplorer.domain.fakes.FakeSyncScheduler
import com.worldexplorer.domain.fakes.FakeTrackRepository
import com.worldexplorer.domain.fakes.FakeTrackingController
import com.worldexplorer.domain.fakes.SequentialIds
import com.worldexplorer.domain.fakes.TestGeo
import com.worldexplorer.domain.geo.ExplorationGrid
import com.worldexplorer.domain.geo.GeoBounds
import com.worldexplorer.domain.model.LocationPermissionState
import com.worldexplorer.domain.model.MapMode
import com.worldexplorer.domain.model.TrackStatus
import com.worldexplorer.domain.result.AppError
import com.worldexplorer.domain.usecase.CalculateExploredAreaUseCase
import com.worldexplorer.domain.usecase.ObserveActiveRouteUseCase
import com.worldexplorer.domain.usecase.ObserveCurrentLocationUseCase
import com.worldexplorer.domain.usecase.ObserveExplorationStatsUseCase
import com.worldexplorer.domain.usecase.ObserveExploredAreaUseCase
import com.worldexplorer.domain.usecase.ObserveSettingsUseCase
import com.worldexplorer.domain.usecase.ObserveTrackingStateUseCase
import com.worldexplorer.domain.usecase.RecoverTrackingUseCase
import com.worldexplorer.domain.usecase.StartTrackingUseCase
import com.worldexplorer.domain.usecase.StopTrackingUseCase
import com.worldexplorer.domain.usecase.SwitchMapModeUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val clock = FakeClock()
    private val exploration = FakeExplorationRepository()
    private val tracks = FakeTrackRepository(exploration)
    private val settings = FakeSettingsRepository()
    private val controller = FakeTrackingController()
    private val location = FakeLocationRepository()
    private val scheduler = FakeSyncScheduler()

    private fun vm(saved: SavedStateHandle = SavedStateHandle()): MapViewModel {
        val dispatchers = AppDispatchers(main.dispatcher, main.dispatcher)
        return MapViewModel(
            ObserveSettingsUseCase(settings),
            ObserveTrackingStateUseCase(controller),
            ObserveActiveRouteUseCase(tracks),
            ObserveExplorationStatsUseCase(exploration),
            ObserveExploredAreaUseCase(exploration, CalculateExploredAreaUseCase(exploration, dispatchers)),
            ObserveCurrentLocationUseCase(location),
            SwitchMapModeUseCase(settings),
            StartTrackingUseCase(tracks, controller, location, clock, SequentialIds("t")),
            StopTrackingUseCase(tracks, controller, settings, scheduler, clock),
            RecoverTrackingUseCase(tracks, controller, location, clock),
            location,
            FakeConnectivity(),
            dispatchers,
            saved,
        )
    }

    @Test
    fun `record without permission asks for permission and starts nothing`() = runTest {
        location.permission = LocationPermissionState(false, false, false, false)
        val vm = vm()
        vm.messages.test {
            vm.onRecordClicked()
            assertEquals(MapEvent.RequestPermissions, awaitItem())
        }
        assertTrue(tracks.tracks.value.isEmpty())
        assertEquals(PermissionStatus.UNKNOWN, vm.state.value.permission)
    }

    @Test
    fun `denied permission result is shown as denied`() = runTest {
        location.permission = LocationPermissionState(false, false, false, false)
        val vm = vm()
        vm.onPermissionsResult()
        val job = backgroundScope.launch(main.dispatcher) { vm.state.collect {} }
        assertEquals(PermissionStatus.DENIED, vm.state.value.permission)
        job.cancel()
    }

    @Test
    fun `record starts tracking and the state shows the active recording`() = runTest {
        val vm = vm()
        val job = backgroundScope.launch(main.dispatcher) { vm.state.collect {} }
        vm.messages.test {
            vm.onRecordClicked()
            assertEquals(MapEvent.Message(UiText.Res(R.string.msg_recording_started)), awaitItem())
        }
        assertEquals(TrackStatus.ACTIVE, tracks.getActiveTrack()?.status)
        assertNotNull(vm.state.value.recording)
        assertTrue(controller.isRecorderRunning)
        job.cancel()
    }

    @Test
    fun `record with location disabled opens location settings and explains why`() = runTest {
        location.gps.value = location.gps.value.copy(providerEnabled = false)
        val vm = vm()
        vm.messages.test {
            vm.onRecordClicked()
            assertEquals(MapEvent.OpenLocationSettings, awaitItem())
            assertEquals(MapEvent.Message(UiText.Error(AppError.LocationDisabled)), awaitItem())
        }
        assertNull(tracks.getActiveTrack())
    }

    @Test
    fun `second click stops recording and reports the distance`() = runTest {
        val vm = vm()
        val job = backgroundScope.launch(main.dispatcher) { vm.state.collect {} }
        vm.messages.test {
            vm.onRecordClicked(); awaitItem()
            vm.onRecordClicked()
            val msg = awaitItem() as MapEvent.Message
            assertEquals(R.string.msg_recording_stopped, (msg.text as UiText.Res).id)
        }
        assertNull(tracks.getActiveTrack())
        assertEquals(TrackStatus.COMPLETED, tracks.tracks.value.values.single().status)
        assertNull(vm.state.value.recording)
        job.cancel()
    }

    @Test
    fun `toggling the mode changes only the display setting`() = runTest {
        exploration.add(ExplorationGrid.cellsWithinRadius(TestGeo.BASE_LAT, TestGeo.BASE_LON, 20.0))
        val cells = exploration.cells.toSet()
        val vm = vm()
        val job = backgroundScope.launch(main.dispatcher) { vm.state.collect {} }
        vm.onToggleMode()
        assertEquals(MapMode.EXPLORER, vm.state.value.mapMode)
        vm.onToggleMode()
        assertEquals(MapMode.FOG_OF_WAR, vm.state.value.mapMode)
        assertEquals(cells, exploration.cells)
        job.cancel()
    }

    @Test
    fun `orphaned active track is resumed on start with a notice`() = runTest {
        tracks.startTrack("orphan", clock.now)
        val vm = vm()
        val event = withTimeoutOrNull(1_000) { vm.messages.firstOrNull() }
        assertEquals(MapEvent.Message(UiText.Res(R.string.msg_recording_resumed)), event)
        assertTrue(controller.isRecorderRunning)
    }

    @Test
    fun `explored territory is delivered as map layers for the viewport`() = runTest {
        exploration.add(ExplorationGrid.cellsWithinRadius(TestGeo.BASE_LAT, TestGeo.BASE_LON, 20.0))
        val vm = vm()
        val job = backgroundScope.launch(main.dispatcher) { vm.layers.collect {} }
        vm.onCameraIdle(GeoBounds(TestGeo.BASE_LAT - 0.001, TestGeo.BASE_LON - 0.001, TestGeo.BASE_LAT + 0.001, TestGeo.BASE_LON + 0.001), SavedCamera(TestGeo.BASE_LAT, TestGeo.BASE_LON, 17.0, 0.0))
        advanceTimeBy(500)
        val layers = vm.layers.value
        assertNotNull(layers)
        assertTrue(layers!!.explored.contains("MultiPolygon"))
        assertTrue(layers.fog.contains("MultiPolygon"))
        job.cancel()
    }

    @Test
    fun `camera survives recreation through saved state and disables follow mode`() = runTest {
        val saved = SavedStateHandle()
        val first = vm(saved)
        assertTrue(first.state.value.followUser)
        first.onCameraIdle(GeoBounds(1.0, 1.0, 2.0, 2.0), SavedCamera(1.5, 1.5, 12.0, 30.0))
        val second = vm(saved)
        assertEquals(SavedCamera(1.5, 1.5, 12.0, 30.0), second.savedCamera())
        val job = backgroundScope.launch(main.dispatcher) { second.state.collect {} }
        assertFalse(second.state.value.followUser)
        job.cancel()
    }
}
