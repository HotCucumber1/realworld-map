package com.worldexplorer.app.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.worldexplorer.app.R
import com.worldexplorer.app.ui.common.AppTopBar
import com.worldexplorer.app.ui.common.Formatters
import com.worldexplorer.app.ui.common.InfoCard
import com.worldexplorer.app.ui.common.SwitchRow
import com.worldexplorer.app.ui.common.asString

@Composable
fun CloudSyncRoute(onBack: () -> Unit, viewModel: CloudSyncViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CloudSyncScreen(state, viewModel, onBack)
}

@Composable
private fun CloudSyncScreen(state: CloudUiState, vm: CloudSyncViewModel, onBack: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(topBar = { AppTopBar(stringResource(R.string.cloud_title), onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.busy || state.status?.running == true) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
            state.message?.let { msg ->
                Text(
                    msg.asString(),
                    color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 20.dp).testTag("cloud_message"),
                )
            }
            val status = state.status
            when {
                status == null -> Unit
                !status.configured -> Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(stringResource(R.string.cloud_not_configured_title), style = MaterialTheme.typography.titleLarge)
                    InfoCard(stringResource(R.string.cloud_not_configured_text), Modifier.padding(top = 12.dp).testTag("cloud_not_configured"), icon = Icons.Filled.CloudOff)
                }
                status.account == null -> SignInForm(state.busy, vm::signIn, vm::signUp)
                else -> {
                    Text(stringResource(R.string.cloud_signed_in_as, status.account?.email.orEmpty()), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp))
                    SwitchRow(stringResource(R.string.cloud_enable), stringResource(R.string.cloud_enable_desc), state.settings.syncEnabled, { vm.setSyncEnabled(it) }, Modifier.testTag("sync_enabled"))
                    SwitchRow(stringResource(R.string.cloud_auto), stringResource(R.string.cloud_auto_desc), state.settings.autoSync, { vm.setAutoSync(it) }, enabled = state.settings.syncEnabled)
                    SwitchRow(stringResource(R.string.cloud_unmetered), null, state.settings.syncOnlyOnUnmetered, { vm.setUnmeteredOnly(it) }, enabled = state.settings.syncEnabled)
                    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = vm::syncNow, enabled = state.settings.syncEnabled && !state.busy && !status.running, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.cloud_sync_now))
                        }
                        if (status.running) Text(stringResource(R.string.cloud_status_running), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        Text(
                            status.lastSuccessAtUtc?.let { stringResource(R.string.cloud_status_last, Formatters.dateTime(it)) } ?: stringResource(R.string.cloud_status_never),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        status.lastError?.let { Text(stringResource(R.string.cloud_status_error, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                        Text(stringResource(R.string.cloud_status_pending, status.pendingTracks, status.pendingPoints.toInt(), status.pendingCells.toInt()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.cloud_active_tracks_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = vm::signOut, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cloud_sign_out)) }
                        TextButton(onClick = { confirm = "data" }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cloud_delete_data), color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = { confirm = "account" }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cloud_delete_account), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            InfoCard(stringResource(R.string.settings_privacy_text), Modifier.padding(horizontal = 20.dp, vertical = 12.dp), icon = Icons.Filled.Info)
        }
    }
    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(if (what == "data") R.string.cloud_delete_data else R.string.cloud_delete_account)) },
            text = { Text(stringResource(if (what == "data") R.string.cloud_delete_data_confirm else R.string.cloud_delete_account_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirm = null; if (what == "data") vm.deleteCloudData() else vm.deleteAccount() }) {
                    Text(stringResource(R.string.action_ok), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun SignInForm(busy: Boolean, onSignIn: (String, String) -> Unit, onSignUp: (String, String) -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(email, { email = it }, label = { Text(stringResource(R.string.cloud_email)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth().testTag("cloud_email"))
        OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.cloud_password)) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onSignIn(email, password) }, enabled = !busy && email.isNotBlank() && password.length >= 6) { Text(stringResource(R.string.cloud_sign_in)) }
            OutlinedButton(onClick = { onSignUp(email, password) }, enabled = !busy && email.isNotBlank() && password.length >= 6) { Text(stringResource(R.string.cloud_sign_up)) }
        }
    }
}
