package com.worldexplorer.app.feature.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.worldexplorer.app.R
import com.worldexplorer.app.ui.common.AppTopBar
import com.worldexplorer.app.ui.common.Formatters
import com.worldexplorer.app.ui.common.InfoCard
import com.worldexplorer.app.ui.common.asString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BackupRoute(onBack: () -> Unit, viewModel: BackupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Storage Access Framework: the user picks the file; no storage permission is needed.
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { viewModel.onExportTo(it.toString()) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.onImportFrom(it.toString()) }
    }
    val exportNow = {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
        exportLauncher.launch("world-explorer-$stamp.zip")
    }
    BackupScreen(
        state = state,
        onBack = onBack,
        onExport = exportNow,
        onImport = { importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
        onMerge = viewModel::onChooseMerge,
        onReplace = viewModel::onChooseReplace,
        onConfirmReplace = viewModel::onConfirmReplace,
        onCancel = viewModel::onCancelImport,
        onDismissResult = viewModel::onDismissResult,
    )
}

@Composable
fun BackupScreen(
    state: BackupUiState,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onMerge: () -> Unit,
    onReplace: () -> Unit,
    onConfirmReplace: () -> Unit,
    onCancel: () -> Unit,
    onDismissResult: () -> Unit,
) {
    Scaffold(topBar = { AppTopBar(stringResource(R.string.backup_title), onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.working) {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("backup_progress"))
                Text(stringResource(R.string.backup_working), style = MaterialTheme.typography.bodyMedium)
            }
            ActionCard(Icons.Filled.FileUpload, stringResource(R.string.backup_export), stringResource(R.string.backup_export_desc), !state.working, onExport, "backup_export")
            ActionCard(Icons.Filled.FileDownload, stringResource(R.string.backup_import), stringResource(R.string.backup_import_desc), !state.working, onImport, "backup_import")

            state.exported?.let { info ->
                ResultCard(true, stringResource(R.string.backup_export_done, info.trackCount, info.pointCount.toInt(), info.cellCount.toInt()), onDismissResult)
            }
            state.imported?.let { s ->
                val text = buildString {
                    append(stringResource(R.string.backup_import_done)).append('\n')
                    append(stringResource(R.string.backup_import_summary, s.tracksInserted, s.tracksUpdated, s.tracksSkipped, s.pointsInserted.toInt(), s.pointsSkipped.toInt(), s.cellsInserted.toInt(), s.cellsSkipped.toInt()))
                    s.safetyBackupPath?.let { append('\n').append(stringResource(R.string.backup_safety_copy, it.substringAfterLast('/'))) }
                }
                ResultCard(true, text, onDismissResult)
            }
            state.error?.let { ResultCard(false, it.asString(), onDismissResult) }

            InfoCard(stringResource(R.string.backup_transfer_title) + "\n\n" + stringResource(R.string.backup_transfer_text), icon = Icons.Filled.PhoneAndroid)
        }
    }

    state.pending?.let { pending ->
        if (!state.confirmReplace) {
            AlertDialog(
                onDismissRequest = onCancel,
                title = { Text(stringResource(R.string.backup_inspect_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            stringResource(
                                R.string.backup_inspect_text, Formatters.dateTime(pending.info.createdAtUtc), pending.info.appVersion,
                                pending.info.trackCount, pending.info.pointCount.toInt(), pending.info.cellCount.toInt(),
                            ),
                        )
                        Text(stringResource(R.string.backup_merge) + ": " + stringResource(R.string.backup_merge_desc), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.backup_replace) + ": " + stringResource(R.string.backup_replace_desc), style = MaterialTheme.typography.bodyMedium)
                    }
                },
                confirmButton = { Button(onClick = onMerge, modifier = Modifier.testTag("import_merge")) { Text(stringResource(R.string.backup_merge)) } },
                dismissButton = {
                    Row {
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                        TextButton(onClick = onReplace, modifier = Modifier.testTag("import_replace")) { Text(stringResource(R.string.backup_replace), color = MaterialTheme.colorScheme.error) }
                    }
                },
            )
        } else {
            var understood by rememberSaveable { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = onCancel,
                title = { Text(stringResource(R.string.backup_replace_confirm_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.backup_replace_confirm_text))
                        TextButton(onClick = { onCancel(); onExport() }, modifier = Modifier.testTag("replace_export_first")) {
                            Text(stringResource(R.string.backup_replace_suggest_export))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = understood, onCheckedChange = { understood = it }, modifier = Modifier.testTag("replace_understood"))
                            Text(stringResource(R.string.backup_replace_confirm_check))
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = onConfirmReplace, enabled = understood, modifier = Modifier.testTag("replace_confirm")) { Text(stringResource(R.string.backup_replace)) }
                },
                dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
    }
}

@Composable
private fun ActionCard(icon: ImageVector, title: String, text: String, enabled: Boolean, onClick: () -> Unit, tag: String) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.padding(start = 12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag)) { Text(title) }
        }
    }
}

@Composable
private fun ResultCard(success: Boolean, text: String, onDismiss: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (success) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.testTag(if (success) "backup_success" else "backup_error"),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            Icon(if (success) Icons.Filled.CheckCircle else Icons.Filled.Error, null)
            Spacer(Modifier.padding(start = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
            }
        }
    }
}
