package com.worldexplorer.app.feature.map

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.worldexplorer.app.R
import com.worldexplorer.app.core.map.ExplorationLayerData
import com.worldexplorer.app.core.map.GeoJsonWriter
import com.worldexplorer.app.core.map.MapStyles
import com.worldexplorer.app.core.map.StyleSource
import com.worldexplorer.app.ui.common.UiText
import com.worldexplorer.domain.AppDispatchers
import com.worldexplorer.domain.geo.ExploredGeometry
import com.worldexplorer.domain.geo.GeoBounds
import com.worldexplorer.domain.model.AppSettings
import com.worldexplorer.domain.model.CurrentLocation
import com.worldexplorer.domain.model.FogStyle
import com.worldexplorer.domain.model.GeoPoint
import com.worldexplorer.domain.model.GpsSignal
import com.worldexplorer.domain.model.MapMode
import com.worldexplorer.domain.model.TrackingState
import com.worldexplorer.domain.repository.ConnectivityRepository
import com.worldexplorer.domain.repository.LocationRepository
import com.worldexplorer.domain.result.AppError
import com.worldexplorer.domain.result.AppResult
import com.worldexplorer.domain.usecase.ActiveRoute
import com.worldexplorer.domain.usecase.ObserveActiveRouteUseCase
import com.worldexplorer.domain.usecase.ObserveCurrentLocationUseCase
import com.worldexplorer.domain.usecase.ObserveExplorationStatsUseCase
import com.worldexplorer.domain.usecase.ObserveExploredAreaUseCase
import com.worldexplorer.domain.usecase.ObserveSettingsUseCase
import com.worldexplorer.domain.usecase.ObserveTrackingStateUseCase
import com.worldexplorer.domain.usecase.RecoverTrackingUseCase
import com.worldexplorer.domain.usecase.RecoveryOutcome
import com.worldexplorer.domain.usecase.StartTrackingUseCase
import com.worldexplorer.domain.usecase.StopTrackingUseCase
import com.worldexplorer.domain.usecase.SwitchMapModeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class PermissionStatus { UNKNOWN, GRANTED, DENIED }

data class RecordingUi(
    val trackId: String,
    val startedAtUtc: Long,
    val distanceMeters: Double,
    val pointCount: Int,
    val signal: GpsSignal,
)

/** Immutable state of the map screen. Heavy layer data is exposed separately ([MapViewModel.layers]). */
data class MapUiState(
    val mapMode: MapMode = MapMode.FOG_OF_WAR,
    val fogStyle: FogStyle = FogStyle.CLOUDS,
    val showTrack: Boolean = true,
    val showHighlight: Boolean = true,
    val style: StyleSource = StyleSource.Url(MapStyles.LIBERTY),
    val permission: PermissionStatus = PermissionStatus.UNKNOWN,
    val gpsEnabled: Boolean = true,
    val online: Boolean = true,
    val starting: Boolean = false,
    val recording: RecordingUi? = null,
    val exploredAreaSquareMeters: Double = 0.0,
    val location: CurrentLocation? = null,
    val followUser: Boolean = true,
    val mapLoadFailed: Boolean = false,
)

data class SavedCamera(val latitude: Double, val longitude: Double, val zoom: Double, val bearing: Double)

sealed interface MapEvent {
    data class Message(val text: UiText) : MapEvent
    /** UI must ask for location (and, on Android 13+, notification) permissions. */
    data object RequestPermissions : MapEvent
    data object OpenLocationSettings : MapEvent
}

@HiltViewModel
class MapViewModel @Inject constructor(
    observeSettings: ObserveSettingsUseCase,
    observeTracking: ObserveTrackingStateUseCase,
    observeActiveRoute: ObserveActiveRouteUseCase,
    observeStats: ObserveExplorationStatsUseCase,
    observeExploredArea: ObserveExploredAreaUseCase,
    private val observeLocation: ObserveCurrentLocationUseCase,
    private val switchMapMode: SwitchMapModeUseCase,
    private val startTracking: StartTrackingUseCase,
    private val stopTracking: StopTrackingUseCase,
    private val recoverTracking: RecoverTrackingUseCase,
    private val location: LocationRepository,
    connectivity: ConnectivityRepository,
    private val dispatchers: AppDispatchers,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val local = MutableStateFlow(
        LocalState(permission = if (hasPermission()) PermissionStatus.GRANTED else PermissionStatus.UNKNOWN, followUser = savedCamera() == null),
    )
    private val viewport = MutableStateFlow<GeoBounds?>(null)
    private val events = Channel<MapEvent>(Channel.BUFFERED)
    val messages: Flow<MapEvent> = events.receiveAsFlow()
    private var locationJob: Job? = null

    /** Active track, kept hot for the whole ViewModel life: record/stop decisions must not depend on UI subscription. */
    private val activeRoute: StateFlow<ActiveRoute?> = observeActiveRoute().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<MapUiState> = combine(
        observeSettings(),
        observeTracking(),
        activeRoute,
        observeStats(),
        combine(location.observeGpsStatus(), connectivity.observeOnline(), local) { gps, online, l -> Triple(gps.providerEnabled, online, l) },
    ) { settings, tracking, route, stats, (gps, online, l) ->
        MapUiState(
            mapMode = settings.mapMode,
            fogStyle = settings.fogStyle,
            showTrack = settings.showTrack,
            showHighlight = settings.showExploredHighlight,
            style = styleOf(settings),
            permission = l.permission,
            gpsEnabled = gps,
            online = online,
            starting = l.starting || tracking is TrackingState.Starting,
            recording = recordingUi(tracking, route),
            exploredAreaSquareMeters = stats.exploredAreaSquareMeters,
            location = l.location,
            followUser = l.followUser,
            mapLoadFailed = l.mapLoadFailed,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapUiState(permission = local.value.permission))

    /** Explored territory serialized for the map; recomputed only for the visible window. */
    val layers: StateFlow<ExplorationLayerData?> = observeExploredArea(viewport.filterNotNull())
        .map { it.toLayerData() }
        .flowOn(dispatchers.default)
        .catch { e -> android.util.Log.e("WorldExplorer", "territory rendering failed", e) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Route of the active track as GeoJSON (line + start marker). */
    val route: StateFlow<Pair<String, String>> = activeRoute
        .map { r ->
            val pts = r?.points.orEmpty().map { GeoPoint(it.latitude, it.longitude) }
            GeoJsonWriter.lineString(pts) to GeoJsonWriter.points(listOfNotNull(pts.firstOrNull()?.let { it to "start" }))
        }
        .flowOn(dispatchers.default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GeoJsonWriter.EMPTY to GeoJsonWriter.EMPTY)

    init {
        viewModelScope.launch {
            when (val outcome = recoverTracking()) {
                is RecoveryOutcome.Resumed -> send(UiText.Res(R.string.msg_recording_resumed))
                is RecoveryOutcome.Interrupted -> send(
                    UiText.Res(if (outcome.reason == AppError.LocationPermissionDenied) R.string.msg_recording_interrupted_permission else R.string.msg_recording_interrupted),
                )
                else -> Unit
            }
        }
    }

    fun savedCamera(): SavedCamera? {
        val lat = savedState.get<Double>(KEY_LAT) ?: return null
        val lon = savedState.get<Double>(KEY_LON) ?: return null
        return SavedCamera(lat, lon, savedState.get<Double>(KEY_ZOOM) ?: 15.0, savedState.get<Double>(KEY_BEARING) ?: 0.0)
    }

    /** Screen became visible: re-check permissions (they can be revoked in system settings). */
    fun onScreenStarted() {
        val granted = hasPermission()
        local.update {
            it.copy(permission = if (granted) PermissionStatus.GRANTED else if (it.permission == PermissionStatus.DENIED) PermissionStatus.DENIED else PermissionStatus.UNKNOWN)
        }
        if (granted) startLocationUpdates()
    }

    fun onScreenStopped() {
        locationJob?.cancel()
        locationJob = null
    }

    fun onPermissionsResult() {
        val granted = hasPermission()
        local.update { it.copy(permission = if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED) }
        if (granted) startLocationUpdates()
    }

    private fun startLocationUpdates() {
        if (locationJob?.isActive == true) return
        locationJob = viewModelScope.launch {
            observeLocation()
                .catch { e ->
                    if (e is SecurityException) local.update { it.copy(permission = PermissionStatus.DENIED) } else throw e
                }
                .collect { loc -> local.update { it.copy(location = loc) } }
        }
    }

    fun onRecordClicked() {
        if (local.value.starting) return
        viewModelScope.launch {
            if (activeRoute.value != null) {
                when (val r = stopTracking()) {
                    is AppResult.Success -> send(UiText.Res(R.string.msg_recording_stopped, listOf(UiText.Distance(r.value.distanceMeters))))
                    is AppResult.Failure -> send(UiText.Error(r.error))
                }
                return@launch
            }
            if (!hasPermission()) {
                events.send(MapEvent.RequestPermissions)
                return@launch
            }
            local.update { it.copy(starting = true, followUser = true) }
            when (val r = startTracking()) {
                is AppResult.Success -> send(UiText.Res(R.string.msg_recording_started))
                is AppResult.Failure -> {
                    if (r.error == AppError.LocationDisabled) events.send(MapEvent.OpenLocationSettings)
                    if (r.error == AppError.LocationPermissionDenied) events.send(MapEvent.RequestPermissions)
                    send(UiText.Error(r.error))
                }
            }
            local.update { it.copy(starting = false) }
        }
    }

    fun onToggleMode() {
        viewModelScope.launch { switchMapMode.toggle() }
    }

    fun onCenterClicked() {
        if (!hasPermission()) {
            viewModelScope.launch { events.send(MapEvent.RequestPermissions) }
            return
        }
        local.update { it.copy(followUser = true) }
    }

    /** The user dragged the map: stop following the position. */
    fun onUserMovedMap() = local.update { it.copy(followUser = false) }

    fun onCameraIdle(bounds: GeoBounds, camera: SavedCamera) {
        savedState[KEY_LAT] = camera.latitude
        savedState[KEY_LON] = camera.longitude
        savedState[KEY_ZOOM] = camera.zoom
        savedState[KEY_BEARING] = camera.bearing
        viewport.value = bounds
    }

    fun onMapLoadFailed() = local.update { it.copy(mapLoadFailed = true) }
    fun onMapLoaded() = local.update { it.copy(mapLoadFailed = false) }

    private suspend fun send(text: UiText) = events.send(MapEvent.Message(text))

    /** UNKNOWN until the user answered a permission request; DENIED after a refusal. */
    private fun hasPermission(): Boolean = location.permissionState().anyLocation

    private data class LocalState(
        val permission: PermissionStatus,
        val followUser: Boolean,
        val starting: Boolean = false,
        val location: CurrentLocation? = null,
        val mapLoadFailed: Boolean = false,
    )

    companion object {
        private const val KEY_LAT = "camera_lat"
        private const val KEY_LON = "camera_lon"
        private const val KEY_ZOOM = "camera_zoom"
        private const val KEY_BEARING = "camera_bearing"

        fun styleOf(s: AppSettings): StyleSource = MapStyles.resolve(s.mapStyle, s.customStyleUrl)

        fun recordingUi(tracking: TrackingState, route: ActiveRoute?): RecordingUi? {
            val track = route?.track ?: return null
            val signal = (tracking as? TrackingState.Recording)?.signal ?: GpsSignal.WAITING
            return RecordingUi(track.id, track.startedAtUtc, track.distanceMeters, track.pointCount, signal)
        }

        fun ExploredGeometry.toLayerData() = ExplorationLayerData(
            fog = GeoJsonWriter.polygons(fog),
            explored = GeoJsonWriter.polygons(explored),
            edges = GeoJsonWriter.lines(boundaries),
        )
    }
}
