package com.worldexplorer.app.feature.map

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationDisabled
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.worldexplorer.app.R
import com.worldexplorer.app.core.map.ExplorationLayerData
import com.worldexplorer.app.core.map.ExplorationMapController
import com.worldexplorer.app.core.map.MapLibreView
import com.worldexplorer.app.core.map.UserMarker
import com.worldexplorer.app.ui.common.Formatters
import com.worldexplorer.app.ui.common.StatValue
import com.worldexplorer.app.ui.common.StatusPill
import com.worldexplorer.app.ui.common.rememberNow
import com.worldexplorer.app.ui.theme.Brand
import com.worldexplorer.domain.model.GpsSignal
import com.worldexplorer.domain.model.MapMode
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

private const val STALE_LOCATION_MILLIS = 60_000L
private const val FOLLOW_ZOOM = 16.5

@Composable
fun MapRoute(onOpenHistory: () -> Unit, onOpenSettings: () -> Unit, viewModel: MapViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val layers by viewModel.layers.collectAsStateWithLifecycle()
    val route by viewModel.route.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.onPermissionsResult() }
    val requestPermissions = {
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    LifecycleStartEffect(Unit) {
        viewModel.onScreenStarted()
        onStopOrDispose { viewModel.onScreenStopped() }
    }
    // Light status bar icons over the dark fog; restored when leaving the map.
    val view = LocalView.current
    DisposableEffect(state.mapMode) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previous = controller?.isAppearanceLightStatusBars
        if (state.mapMode == MapMode.FOG_OF_WAR) controller?.isAppearanceLightStatusBars = false
        onDispose { if (previous != null) controller.isAppearanceLightStatusBars = previous }
    }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { event ->
            when (event) {
                is MapEvent.Message -> snackbar.showSnackbar(event.text.resolve(context))
                MapEvent.RequestPermissions -> requestPermissions()
                MapEvent.OpenLocationSettings -> runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        ExplorationMap(state, layers, route, viewModel)
        MapOverlay(
            state = state,
            onRecord = viewModel::onRecordClicked,
            onToggleMode = viewModel::onToggleMode,
            onCenter = viewModel::onCenterClicked,
            onHistory = onOpenHistory,
            onSettings = onOpenSettings,
            onRequestPermission = requestPermissions,
            onOpenLocationSettings = { runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } },
            onRetryMap = viewModel::onMapLoaded,
            snackbar = { SnackbarHost(snackbar) },
        )
    }
}

/** Binds the MapLibre view to the screen state through [ExplorationMapController]. */
@Composable
private fun ExplorationMap(state: MapUiState, layers: ExplorationLayerData?, route: Pair<String, String>, viewModel: MapViewModel) {
    var controller by remember { mutableStateOf<ExplorationMapController?>(null) }
    var styleGeneration by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val now = rememberNow()

    MapLibreView(
        modifier = Modifier.fillMaxSize().testTag("map"),
        onReady = { c ->
            val saved = viewModel.savedCamera()
            if (saved != null) {
                c.restore(CameraPosition.Builder().target(LatLng(saved.latitude, saved.longitude)).zoom(saved.zoom).bearing(saved.bearing).build())
            } else {
                c.moveTo(30.0, 10.0, 1.5, animate = false)
            }
            with(density) { c.setUiInsets(topPx = 64.dp.roundToPx() + 24.dp.roundToPx(), startPx = 12.dp.roundToPx(), endPx = 12.dp.roundToPx()) }
            c.map.addOnCameraMoveStartedListener { reason -> if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) viewModel.onUserMovedMap() }
            c.map.addOnCameraIdleListener { reportViewport(c, viewModel) }
            controller = c
        },
        onLoadFailed = { viewModel.onMapLoadFailed() },
    )

    val c = controller ?: return
    LaunchedEffect(c, state.style, styleGeneration, state.mapLoadFailed) {
        if (!state.mapLoadFailed) c.loadStyle(state.style) { reportViewport(c, viewModel) }
    }
    LaunchedEffect(c, layers) { layers?.let(c::setExploration) }
    LaunchedEffect(c, state.mapMode, state.fogStyle, state.showHighlight, state.showTrack) {
        c.setAppearance(state.mapMode, state.fogStyle, state.showHighlight, state.showTrack)
    }
    LaunchedEffect(c, route) { c.setRoute(route.first, route.second) }
    val location = state.location
    val stale = location != null && now - location.timestampUtcMillis > STALE_LOCATION_MILLIS
    LaunchedEffect(c, location, stale) {
        c.setUser(location?.let { UserMarker(it.point.latitude, it.point.longitude, it.accuracyMeters, stale) })
    }
    LaunchedEffect(c, location, state.followUser) {
        if (state.followUser && location != null) {
            val zoom = maxOf(c.camera().zoom, FOLLOW_ZOOM)
            c.moveTo(location.point.latitude, location.point.longitude, zoom, animate = true)
        }
    }
}

private fun reportViewport(c: ExplorationMapController, viewModel: MapViewModel) {
    val bounds = c.visibleBounds() ?: return
    val cam = c.camera()
    val target = cam.target ?: return
    viewModel.onCameraIdle(bounds, SavedCamera(target.latitude, target.longitude, cam.zoom, cam.bearing))
}

/** Controls and status over the map. Stateless, so it is testable without a map. */
@Composable
fun MapOverlay(
    state: MapUiState,
    onRecord: () -> Unit,
    onToggleMode: () -> Unit,
    onCenter: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onRetryMap: () -> Unit,
    snackbar: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val now = rememberNow()
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GpsPill(state, now)
                if (!state.online) StatusPill(stringResource(R.string.status_offline), Icons.Filled.WifiOff, MaterialTheme.colorScheme.secondary, Modifier.testTag("offline_status"))
            }
            AnimatedVisibility(state.permission != PermissionStatus.GRANTED) {
                Banner(stringResource(R.string.map_permission_banner), Icons.Filled.LocationDisabled, stringResource(R.string.action_grant), onRequestPermission, Modifier.testTag("permission_banner"))
            }
            AnimatedVisibility(state.permission == PermissionStatus.GRANTED && !state.gpsEnabled) {
                Banner(stringResource(R.string.map_gps_off_banner), Icons.Filled.GpsOff, stringResource(R.string.action_open_settings), onOpenLocationSettings, Modifier.testTag("gps_off_banner"))
            }
            AnimatedVisibility(state.mapLoadFailed) {
                Banner(stringResource(R.string.map_load_failed), Icons.Filled.WarningAmber, stringResource(R.string.action_retry), onRetryMap, Modifier.testTag("map_failed_banner"))
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp).fillMaxWidth()) {
            Box(Modifier.fillMaxWidth()) { snackbar() }
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        val rec = state.recording
                        if (rec != null) {
                            StatValue(stringResource(R.string.stat_distance), Formatters.distance(context, rec.distanceMeters), Modifier.testTag("stat_distance"))
                            StatValue(stringResource(R.string.stat_duration), Formatters.duration(context, now - rec.startedAtUtc))
                        }
                        StatValue(stringResource(R.string.stat_explored), Formatters.area(context, state.exploredAreaSquareMeters), Modifier.testTag("explored_area"))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        RoundAction(Icons.Filled.History, stringResource(R.string.map_history), onHistory, Modifier.testTag("history_button"))
                        ModeToggle(state.mapMode, onToggleMode)
                        RecordButton(state, onRecord)
                        RoundAction(
                            if (state.followUser && state.location != null) Icons.Filled.GpsFixed else Icons.Filled.MyLocation,
                            stringResource(R.string.map_center), onCenter, Modifier.testTag("center_button"),
                        )
                        RoundAction(Icons.Filled.Settings, stringResource(R.string.map_settings), onSettings, Modifier.testTag("settings_button"))
                    }
                }
            }
        }
    }
}

@Composable
private fun GpsPill(state: MapUiState, now: Long) {
    val scheme = MaterialTheme.colorScheme
    val loc = state.location
    val age = loc?.let { ((now - it.timestampUtcMillis) / 1000).coerceAtLeast(0) }
    val ageText = when {
        age == null -> null
        age < 3 -> stringResource(R.string.status_location_now)
        else -> stringResource(R.string.status_location_age, Formatters.duration(LocalContext.current, age * 1000))
    }
    val (text, icon, tint) = when {
        state.permission != PermissionStatus.GRANTED -> Triple(stringResource(R.string.status_no_permission), Icons.Filled.LocationDisabled, scheme.error)
        !state.gpsEnabled -> Triple(stringResource(R.string.status_gps_off), Icons.Filled.GpsOff, scheme.error)
        state.recording?.signal == GpsSignal.LOST -> Triple(stringResource(R.string.status_gps_lost), Icons.Filled.GpsNotFixed, scheme.secondary)
        loc == null -> Triple(stringResource(R.string.status_gps_waiting), Icons.Filled.GpsNotFixed, scheme.secondary)
        else -> Triple(
            listOfNotNull(stringResource(if (state.recording != null) R.string.status_recording else R.string.status_gps_ok), ageText).joinToString(" · "),
            Icons.Filled.GpsFixed,
            if (age != null && age * 1000 > STALE_LOCATION_MILLIS) scheme.secondary else scheme.primary,
        )
    }
    StatusPill(text, icon, tint, Modifier.testTag("gps_status"))
}

@Composable
private fun Banner(text: String, icon: ImageVector, action: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun RoundAction(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp)) { Icon(icon, contentDescription = description) }
}

@Composable
private fun ModeToggle(mode: MapMode, onToggle: () -> Unit) {
    val label = stringResource(if (mode == MapMode.FOG_OF_WAR) R.string.mode_fog else R.string.mode_explorer)
    val description = stringResource(R.string.map_toggle_mode)
    FilledTonalButton(
        onClick = onToggle,
        modifier = Modifier.testTag("mode_toggle").semantics { contentDescription = "$description: $label" },
    ) {
        Icon(if (mode == MapMode.FOG_OF_WAR) Icons.Filled.Cloud else Icons.Filled.Map, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

@Composable
private fun RecordButton(state: MapUiState, onRecord: () -> Unit) {
    val recording = state.recording != null
    val description = stringResource(if (recording) R.string.map_record_stop else R.string.map_record_start)
    LargeFloatingActionButton(
        onClick = onRecord,
        shape = CircleShape,
        containerColor = if (recording) Brand.Red else MaterialTheme.colorScheme.primary,
        contentColor = if (recording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.size(72.dp).testTag("record_button").semantics { contentDescription = description },
    ) {
        when {
            state.starting -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.onPrimary)
            recording -> Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(34.dp))
            else -> Icon(Icons.Filled.FiberManualRecord, contentDescription = null, modifier = Modifier.size(30.dp))
        }
    }
}
