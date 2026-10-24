package com.worldexplorer.app.feature.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.worldexplorer.app.R
import com.worldexplorer.app.core.map.ExplorationMapController
import com.worldexplorer.app.core.map.MapLibreView
import com.worldexplorer.app.ui.common.AppTopBar
import com.worldexplorer.app.ui.common.Formatters
import com.worldexplorer.app.ui.common.SectionHeader
import com.worldexplorer.app.ui.common.StatValue
import com.worldexplorer.app.ui.common.rememberNow
import com.worldexplorer.domain.model.FogStyle
import com.worldexplorer.domain.model.MapMode
import com.worldexplorer.domain.model.Track
import com.worldexplorer.domain.model.TrackStatus
import org.maplibre.android.geometry.LatLng

@Composable
fun HistoryRoute(onBack: () -> Unit, onOpenTrack: (String) -> Unit, viewModel: HistoryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HistoryScreen(state, onBack, onOpenTrack)
}

@Composable
fun HistoryScreen(state: HistoryUiState, onBack: () -> Unit, onOpenTrack: (String) -> Unit) {
    Scaffold(topBar = { AppTopBar(stringResource(R.string.history_title), onBack) }) { padding ->
        when (state) {
            HistoryUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is HistoryUiState.Loaded -> if (state.tracks.isEmpty()) {
                EmptyHistory(Modifier.padding(padding))
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding).testTag("history_list"),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.tracks, key = { it.id }) { TrackCard(it) { onOpenTrack(it.id) } }
                }
            }
        }
    }
}

@Composable
private fun EmptyHistory(modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(32.dp).testTag("history_empty"), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Route, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.history_empty_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.history_empty_text), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TrackCard(track: Track, onClick: () -> Unit) {
    val context = LocalContext.current
    val now = rememberNow()
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("track_${track.id}")) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Formatters.dateTime(track.startedAtUtc), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusChip(track.status)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatValue(stringResource(R.string.stat_duration), Formatters.duration(context, track.durationMillis(now)))
                StatValue(stringResource(R.string.stat_distance), Formatters.distance(context, track.distanceMeters))
                StatValue(stringResource(R.string.stat_points), Formatters.count(track.pointCount.toLong()))
            }
        }
    }
}

@Composable
private fun StatusChip(status: TrackStatus) {
    val label = when (status) {
        TrackStatus.ACTIVE -> R.string.history_active
        TrackStatus.INTERRUPTED -> R.string.history_interrupted
        TrackStatus.COMPLETED -> return
    }
    AssistChip(
        onClick = {},
        label = { Text(stringResource(label)) },
        colors = AssistChipDefaults.assistChipColors(
            labelColor = if (status == TrackStatus.ACTIVE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
        ),
    )
}

@Composable
fun TrackDetailRoute(trackId: String, onBack: () -> Unit, viewModel: TrackDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TrackDetailScreen(state, onBack, map = { s -> TrackMap(s) })
}

@Composable
fun TrackDetailScreen(state: TrackDetailUiState, onBack: () -> Unit, map: @Composable (TrackDetailUiState.Loaded) -> Unit) {
    val context = LocalContext.current
    val now = rememberNow()
    Scaffold(topBar = { AppTopBar(stringResource(R.string.detail_title), onBack) }) { padding ->
        when (state) {
            TrackDetailUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            TrackDetailUiState.NotFound -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text(stringResource(R.string.detail_not_found)) }
            is TrackDetailUiState.Loaded -> {
                val track = state.details.track
                val q = state.details.quality
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
                    Box(Modifier.fillMaxWidth().height(320.dp)) {
                        if (state.details.points.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.detail_no_points)) }
                        } else {
                            map(state)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        StatValue(stringResource(R.string.detail_distance), Formatters.distance(context, track.distanceMeters), Modifier.testTag("detail_distance"))
                        StatValue(stringResource(R.string.detail_duration), Formatters.duration(context, track.durationMillis(now)))
                        StatValue(stringResource(R.string.detail_points), Formatters.count(track.pointCount.toLong()), Modifier.testTag("detail_points"))
                    }
                    HorizontalDivider()
                    DetailLine(stringResource(R.string.detail_started), Formatters.dateTime(track.startedAtUtc))
                    DetailLine(
                        stringResource(R.string.detail_ended),
                        if (track.status == TrackStatus.ACTIVE) stringResource(R.string.detail_recording_now) else track.endedAtUtc?.let(Formatters::dateTime) ?: "—",
                    )
                    if (track.status == TrackStatus.INTERRUPTED) DetailLine(stringResource(R.string.history_interrupted), "")
                    SectionHeader(stringResource(R.string.detail_quality))
                    DetailLine(stringResource(R.string.detail_avg_accuracy), Formatters.accuracy(context, q.averageAccuracyMeters))
                    DetailLine(stringResource(R.string.detail_best_accuracy), Formatters.accuracy(context, q.bestAccuracyMeters))
                    DetailLine(stringResource(R.string.detail_worst_accuracy), Formatters.accuracy(context, q.worstAccuracyMeters))
                    DetailLine(stringResource(R.string.detail_precise_share), Formatters.percent(q.preciseShare))
                    DetailLine(stringResource(R.string.detail_rejected), Formatters.count(track.rejectedPointCount.toLong()))
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun TrackMap(state: TrackDetailUiState.Loaded) {
    var controller by remember { mutableStateOf<ExplorationMapController?>(null) }
    var fitted by remember { mutableStateOf(false) }
    val padding = with(LocalDensity.current) { 40.dp.roundToPx() }
    MapLibreView(Modifier.fillMaxSize().testTag("track_map"), onReady = { controller = it })
    val c = controller ?: return
    LaunchedEffect(c, state.style) {
        c.loadStyle(state.style) {}
        c.setAppearance(MapMode.EXPLORER, FogStyle.DARK, showHighlight = false, showRoute = true)
    }
    LaunchedEffect(c, state.routeJson) {
        c.setRoute(state.routeJson, state.markersJson)
        if (!fitted) {
            c.fit(state.details.points.map { LatLng(it.latitude, it.longitude) }, padding)
            fitted = true
        }
    }
}
