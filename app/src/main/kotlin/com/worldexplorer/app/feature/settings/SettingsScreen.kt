package com.worldexplorer.app.feature.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.worldexplorer.app.BuildConfig
import com.worldexplorer.app.R
import com.worldexplorer.app.core.map.MapStyles
import com.worldexplorer.app.ui.common.AppTopBar
import com.worldexplorer.app.ui.common.SectionHeader
import com.worldexplorer.app.ui.common.SettingRow
import com.worldexplorer.app.ui.common.SwitchRow
import com.worldexplorer.domain.model.ExploreRadius
import com.worldexplorer.domain.model.FogStyle
import com.worldexplorer.domain.model.MapMode
import com.worldexplorer.domain.model.MapStyle
import com.worldexplorer.domain.model.TrackingProfile

@Composable
fun SettingsRoute(onBack: () -> Unit, onOpenBackup: () -> Unit, onOpenCloud: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var batteryExempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    LifecycleResumeEffect(Unit) {
        viewModel.refreshPermissions()
        batteryExempt = isIgnoringBatteryOptimizations(context)
        onPauseOrDispose { }
    }
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refreshPermissions() }
    SettingsScreen(
        state = state,
        batteryExempt = batteryExempt,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        onOpenBackup = onOpenBackup,
        onOpenCloud = onOpenCloud,
        onRequestBackgroundLocation = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // On Android 11+ the system shows the app's location settings page ("Allow all the time").
                backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        },
        onOpenBatterySettings = {
            runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                .onFailure { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) }
        },
    )
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    batteryExempt: Boolean,
    onEvent: (SettingsEvent) -> Unit,
    onBack: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenCloud: () -> Unit,
    onRequestBackgroundLocation: () -> Unit,
    onOpenBatterySettings: () -> Unit,
) {
    val s = state.settings
    var styleDialog by rememberSaveable { mutableStateOf(false) }
    var customDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(topBar = { AppTopBar(stringResource(R.string.settings_title), onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).testTag("settings_list")) {
            SectionHeader(stringResource(R.string.settings_section_map))
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.settings_map_mode), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(MapMode.FOG_OF_WAR to R.string.mode_fog, MapMode.EXPLORER to R.string.mode_explorer).forEachIndexed { i, (mode, label) ->
                        SegmentedButton(
                            selected = s.mapMode == mode,
                            onClick = { onEvent(SettingsEvent.SetMapMode(mode)) },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                            modifier = Modifier.testTag("settings_mode_${mode.name}"),
                        ) { Text(stringResource(label)) }
                    }
                }
            }
            SettingRow(stringResource(R.string.settings_map_style), stringResource(styleLabel(s.mapStyle)), Icons.Filled.Layers, onClick = { styleDialog = true })
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.settings_fog_style), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(FogStyle.CLOUDS to R.string.fog_clouds, FogStyle.DARK to R.string.fog_dark).forEachIndexed { i, (style, label) ->
                        SegmentedButton(selected = s.fogStyle == style, onClick = { onEvent(SettingsEvent.SetFogStyle(style)) }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(stringResource(label)) }
                    }
                }
            }
            SwitchRow(stringResource(R.string.settings_show_track), stringResource(R.string.settings_show_track_desc), s.showTrack, { onEvent(SettingsEvent.SetShowTrack(it)) })
            SwitchRow(stringResource(R.string.settings_show_highlight), stringResource(R.string.settings_show_highlight_desc), s.showExploredHighlight, { onEvent(SettingsEvent.SetShowHighlight(it)) })

            SectionHeader(stringResource(R.string.settings_section_exploration))
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.settings_radius), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.settings_radius_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExploreRadius.OPTIONS.forEach { r ->
                        FilterChip(
                            selected = s.exploreRadiusMeters == r,
                            onClick = { onEvent(SettingsEvent.SetRadius(r)) },
                            label = { Text(stringResource(R.string.settings_radius_value, r)) },
                            modifier = Modifier.testTag("radius_$r"),
                        )
                    }
                }
            }

            SectionHeader(stringResource(R.string.settings_section_tracking))
            Text(stringResource(R.string.settings_profile), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 20.dp))
            listOf(
                Triple(TrackingProfile.PRECISE, R.string.profile_precise, R.string.profile_precise_desc),
                Triple(TrackingProfile.BALANCED, R.string.profile_balanced, R.string.profile_balanced_desc),
                Triple(TrackingProfile.BATTERY_SAVER, R.string.profile_battery, R.string.profile_battery_desc),
            ).forEach { (profile, title, desc) ->
                Row(
                    Modifier.fillMaxWidth().selectable(s.trackingProfile == profile, role = Role.RadioButton) { onEvent(SettingsEvent.SetProfile(profile)) }
                        .padding(horizontal = 12.dp, vertical = 4.dp).testTag("profile_${profile.name}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = s.trackingProfile == profile, onClick = null)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text(
                stringResource(R.string.settings_profile_adaptive), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            SettingRow(
                stringResource(R.string.settings_background_location),
                stringResource(if (state.backgroundLocationGranted) R.string.settings_background_location_on else R.string.settings_background_location_off),
                Icons.Filled.LocationOn,
                onClick = if (state.backgroundLocationGranted) null else onRequestBackgroundLocation,
            )
            SettingRow(
                stringResource(R.string.settings_battery),
                stringResource(if (batteryExempt) R.string.settings_battery_on else R.string.settings_battery_off),
                Icons.Filled.BatteryAlert,
                onClick = onOpenBatterySettings,
            )

            SectionHeader(stringResource(R.string.settings_section_data))
            SettingRow(stringResource(R.string.settings_backup), stringResource(R.string.settings_backup_desc), Icons.Filled.SaveAlt, Modifier.testTag("open_backup"), onClick = onOpenBackup) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
            SettingRow(
                stringResource(R.string.settings_cloud),
                when {
                    !state.cloudConfigured -> stringResource(R.string.settings_cloud_not_configured)
                    s.syncEnabled && state.cloudAccount != null -> stringResource(R.string.settings_cloud_on, state.cloudAccount)
                    else -> stringResource(R.string.settings_cloud_off)
                },
                Icons.Filled.CloudSync,
                Modifier.testTag("open_cloud"),
                onClick = onOpenCloud,
            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }

            SectionHeader(stringResource(R.string.settings_section_about))
            SettingRow(stringResource(R.string.settings_version), BuildConfig.VERSION_NAME, Icons.Filled.Info)
            SettingRow(stringResource(R.string.settings_map_data), stringResource(R.string.settings_map_data_text), Icons.Filled.Public)
            SettingRow(stringResource(R.string.settings_privacy), stringResource(R.string.settings_privacy_text), Icons.Filled.PrivacyTip)
            Spacer(Modifier.height(32.dp))
        }
    }

    if (styleDialog) {
        AlertDialog(
            onDismissRequest = { styleDialog = false },
            title = { Text(stringResource(R.string.settings_map_style)) },
            text = {
                Column {
                    MapStyle.entries.forEach { style ->
                        Row(
                            Modifier.fillMaxWidth().selectable(s.mapStyle == style, role = Role.RadioButton) {
                                styleDialog = false
                                if (style == MapStyle.CUSTOM) customDialog = true else onEvent(SettingsEvent.SetMapStyle(style))
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = s.mapStyle == style, onClick = null)
                            Text(stringResource(styleLabel(style)), Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { styleDialog = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }
    if (customDialog) {
        var url by rememberSaveable { mutableStateOf(s.customStyleUrl) }
        val valid = MapStyles.isAllowedCustomUrl(url.trim())
        AlertDialog(
            onDismissRequest = { customDialog = false },
            title = { Text(stringResource(R.string.settings_custom_style)) },
            text = {
                OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true, placeholder = { Text(stringResource(R.string.settings_custom_style_hint)) }, isError = url.isNotBlank() && !valid)
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = { onEvent(SettingsEvent.SetCustomStyleUrl(url.trim())); customDialog = false }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { customDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private fun styleLabel(style: MapStyle) = when (style) {
    MapStyle.OPENFREEMAP_LIBERTY -> R.string.style_liberty
    MapStyle.OPENFREEMAP_POSITRON -> R.string.style_positron
    MapStyle.OPENFREEMAP_BRIGHT -> R.string.style_bright
    MapStyle.OSM_RASTER -> R.string.style_osm_raster
    MapStyle.CUSTOM -> R.string.style_custom
}
