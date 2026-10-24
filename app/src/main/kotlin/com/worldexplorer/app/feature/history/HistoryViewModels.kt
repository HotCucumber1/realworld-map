package com.worldexplorer.app.feature.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.worldexplorer.app.core.map.GeoJsonWriter
import com.worldexplorer.app.core.map.MapStyles
import com.worldexplorer.app.core.map.StyleSource
import com.worldexplorer.app.ui.navigation.TrackDetailDest
import com.worldexplorer.domain.model.GeoPoint
import com.worldexplorer.domain.model.Track
import com.worldexplorer.domain.model.TrackDetails
import com.worldexplorer.domain.usecase.ObserveTrackDetailsUseCase
import com.worldexplorer.domain.usecase.ObserveSettingsUseCase
import com.worldexplorer.domain.usecase.ObserveTrackHistoryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface HistoryUiState {
    data object Loading : HistoryUiState
    data class Loaded(val tracks: List<Track>) : HistoryUiState
}

@HiltViewModel
class HistoryViewModel @Inject constructor(observeHistory: ObserveTrackHistoryUseCase) : ViewModel() {
    val state: StateFlow<HistoryUiState> = observeHistory()
        .map<List<Track>, HistoryUiState> { HistoryUiState.Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState.Loading)
}

sealed interface TrackDetailUiState {
    data object Loading : TrackDetailUiState
    data object NotFound : TrackDetailUiState
    data class Loaded(val details: TrackDetails, val routeJson: String, val markersJson: String, val style: StyleSource) : TrackDetailUiState
}

@HiltViewModel
class TrackDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    observeDetails: ObserveTrackDetailsUseCase,
    observeSettings: ObserveSettingsUseCase,
) : ViewModel() {
    val trackId: String = savedState.toRoute<TrackDetailDest>().trackId

    val state: StateFlow<TrackDetailUiState> = combine(observeDetails(trackId), observeSettings()) { details, settings -> details to MapStyles.resolve(settings.mapStyle, settings.customStyleUrl) }
        .map { (details, style) ->
            if (details == null) {
                TrackDetailUiState.NotFound
            } else {
                val pts = details.points.map { GeoPoint(it.latitude, it.longitude) }
                val markers = buildList {
                    pts.firstOrNull()?.let { add(it to "start") }
                    if (pts.size > 1) add(pts.last() to "end")
                }
                TrackDetailUiState.Loaded(details, GeoJsonWriter.lineString(pts), GeoJsonWriter.points(markers), style)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrackDetailUiState.Loading)
}
