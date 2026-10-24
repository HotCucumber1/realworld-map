package com.worldexplorer.app.feature.settings

import com.worldexplorer.app.MainDispatcherRule
import com.worldexplorer.domain.fakes.FakeLocationRepository
import com.worldexplorer.domain.fakes.FakeSettingsRepository
import com.worldexplorer.domain.fakes.FakeSyncScheduler
import com.worldexplorer.domain.model.FogStyle
import com.worldexplorer.domain.model.MapMode
import com.worldexplorer.domain.model.MapStyle
import com.worldexplorer.domain.model.TrackingProfile
import com.worldexplorer.domain.repository.SyncRepository
import com.worldexplorer.domain.sync.SyncStatus
import com.worldexplorer.domain.usecase.ObserveSettingsUseCase
import com.worldexplorer.domain.usecase.SwitchMapModeUseCase
import com.worldexplorer.domain.usecase.UpdateSettingsUseCase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val settings = FakeSettingsRepository()
    private val sync = mockk<SyncRepository> { every { observeStatus() } returns flowOf(SyncStatus(false, null, false, null, null, null, 0, 0, 0)) }
    // Created lazily: the Main dispatcher rule must be active before viewModelScope is touched.
    private val vm by lazy { SettingsViewModel(ObserveSettingsUseCase(settings), UpdateSettingsUseCase(settings, FakeSyncScheduler()), SwitchMapModeUseCase(settings), FakeLocationRepository(), sync) }

    @Test
    fun `every setting event is persisted`() {
        vm.onEvent(SettingsEvent.SetRadius(50))
        vm.onEvent(SettingsEvent.SetProfile(TrackingProfile.PRECISE))
        vm.onEvent(SettingsEvent.SetMapMode(MapMode.EXPLORER))
        vm.onEvent(SettingsEvent.SetFogStyle(FogStyle.DARK))
        vm.onEvent(SettingsEvent.SetShowTrack(false))
        vm.onEvent(SettingsEvent.SetShowHighlight(false))
        vm.onEvent(SettingsEvent.SetMapStyle(MapStyle.OSM_RASTER))
        val s = settings.state.value
        assertEquals(50, s.exploreRadiusMeters)
        assertEquals(TrackingProfile.PRECISE, s.trackingProfile)
        assertEquals(MapMode.EXPLORER, s.mapMode)
        assertEquals(FogStyle.DARK, s.fogStyle)
        assertEquals(false, s.showTrack)
        assertEquals(false, s.showExploredHighlight)
        assertEquals(MapStyle.OSM_RASTER, s.mapStyle)
    }

    @Test
    fun `insecure custom style URL is ignored`() {
        vm.onEvent(SettingsEvent.SetCustomStyleUrl("http://insecure.example/style.json"))
        assertEquals(MapStyle.OPENFREEMAP_LIBERTY, settings.state.value.mapStyle)
        vm.onEvent(SettingsEvent.SetCustomStyleUrl("https://tiles.example/style.json"))
        assertEquals(MapStyle.CUSTOM, settings.state.value.mapStyle)
        assertEquals("https://tiles.example/style.json", settings.state.value.customStyleUrl)
    }
}
