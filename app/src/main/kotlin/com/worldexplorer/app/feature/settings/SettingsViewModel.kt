package com.worldexplorer.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.worldexplorer.app.core.map.MapStyles
import com.worldexplorer.domain.model.AppSettings
import com.worldexplorer.domain.model.FogStyle
import com.worldexplorer.domain.model.MapMode
import com.worldexplorer.domain.model.MapStyle
import com.worldexplorer.domain.model.TrackingProfile
import com.worldexplorer.domain.repository.LocationRepository
import com.worldexplorer.domain.repository.SyncRepository
import com.worldexplorer.domain.usecase.ObserveSettingsUseCase
import com.worldexplorer.domain.usecase.SwitchMapModeUseCase
import com.worldexplorer.domain.usecase.UpdateSettingsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val loaded: Boolean = false,
    val backgroundLocationGranted: Boolean = false,
    val cloudConfigured: Boolean = false,
    val cloudAccount: String? = null,
)

sealed interface SettingsEvent {
    data class SetMapMode(val mode: MapMode) : SettingsEvent
    data class SetMapStyle(val style: MapStyle) : SettingsEvent
    data class SetCustomStyleUrl(val url: String) : SettingsEvent
    data class SetFogStyle(val style: FogStyle) : SettingsEvent
    data class SetRadius(val meters: Int) : SettingsEvent
    data class SetProfile(val profile: TrackingProfile) : SettingsEvent
    data class SetShowTrack(val show: Boolean) : SettingsEvent
    data class SetShowHighlight(val show: Boolean) : SettingsEvent
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    observeSettings: ObserveSettingsUseCase,
    private val updateSettings: UpdateSettingsUseCase,
    private val switchMapMode: SwitchMapModeUseCase,
    private val location: LocationRepository,
    sync: SyncRepository,
) : ViewModel() {
    private val background = MutableStateFlow(location.permissionState().background)

    val state: StateFlow<SettingsUiState> = combine(observeSettings(), background, sync.observeStatus()) { s, bg, status ->
        SettingsUiState(s, loaded = true, backgroundLocationGranted = bg, cloudConfigured = status.configured, cloudAccount = status.account?.email)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun refreshPermissions() {
        background.value = location.permissionState().background
    }

    fun onEvent(event: SettingsEvent) {
        viewModelScope.launch {
            when (event) {
                is SettingsEvent.SetMapMode -> switchMapMode(event.mode)
                is SettingsEvent.SetMapStyle -> updateSettings { it.copy(mapStyle = event.style) }
                is SettingsEvent.SetCustomStyleUrl -> if (MapStyles.isAllowedCustomUrl(event.url.trim())) {
                    updateSettings { it.copy(customStyleUrl = event.url, mapStyle = MapStyle.CUSTOM) }
                }
                is SettingsEvent.SetFogStyle -> updateSettings { it.copy(fogStyle = event.style) }
                is SettingsEvent.SetRadius -> updateSettings { it.copy(exploreRadiusMeters = event.meters) }
                is SettingsEvent.SetProfile -> updateSettings { it.copy(trackingProfile = event.profile) }
                is SettingsEvent.SetShowTrack -> updateSettings { it.copy(showTrack = event.show) }
                is SettingsEvent.SetShowHighlight -> updateSettings { it.copy(showExploredHighlight = event.show) }
            }
        }
    }
}
