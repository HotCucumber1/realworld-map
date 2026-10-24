package com.worldexplorer.app.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.worldexplorer.app.R
import com.worldexplorer.app.ui.common.UiText
import com.worldexplorer.domain.model.AppSettings
import com.worldexplorer.domain.repository.SyncRepository
import com.worldexplorer.domain.result.AppResult
import com.worldexplorer.domain.sync.SignUpOutcome
import com.worldexplorer.domain.sync.SyncStatus
import com.worldexplorer.domain.usecase.ObserveSettingsUseCase
import com.worldexplorer.domain.usecase.SyncDataUseCase
import com.worldexplorer.domain.usecase.UpdateSettingsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CloudUiState(
    val status: SyncStatus? = null,
    val settings: AppSettings = AppSettings(),
    val busy: Boolean = false,
    val message: UiText? = null,
    val isError: Boolean = false,
)

@HiltViewModel
class CloudSyncViewModel @Inject constructor(
    private val sync: SyncRepository,
    private val syncData: SyncDataUseCase,
    observeSettings: ObserveSettingsUseCase,
    private val updateSettings: UpdateSettingsUseCase,
) : ViewModel() {
    private val local = MutableStateFlow(CloudUiState())

    val state: StateFlow<CloudUiState> = combine(sync.observeStatus(), observeSettings(), local) { status, settings, l ->
        l.copy(status = status, settings = settings)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CloudUiState())

    fun signIn(email: String, password: String) = busy {
        when (val r = sync.signIn(email, password)) {
            is AppResult.Success -> ok(null)
            is AppResult.Failure -> fail(UiText.Error(r.error))
        }
    }

    fun signUp(email: String, password: String) = busy {
        when (val r = sync.signUp(email, password)) {
            is AppResult.Success -> ok(if (r.value == SignUpOutcome.CONFIRMATION_REQUIRED) UiText.Res(R.string.cloud_confirm_email) else null)
            is AppResult.Failure -> fail(UiText.Error(r.error))
        }
    }

    /** Explicit consent: location data is uploaded only after the user turns this on. */
    fun setSyncEnabled(enabled: Boolean) = viewModelScope.launch {
        updateSettings { it.copy(syncEnabled = enabled) }
        if (enabled) syncNow()
    }

    fun setAutoSync(enabled: Boolean) = viewModelScope.launch { updateSettings { it.copy(autoSync = enabled) } }
    fun setUnmeteredOnly(enabled: Boolean) = viewModelScope.launch { updateSettings { it.copy(syncOnlyOnUnmetered = enabled) } }

    fun syncNow() = busy {
        when (val r = syncData()) {
            is AppResult.Success -> ok(UiText.Res(R.string.cloud_sync_done, listOf(r.value.pushedPoints.toInt(), r.value.pulledPoints.toInt())))
            is AppResult.Failure -> fail(UiText.Error(r.error))
        }
    }

    fun signOut() = busy { sync.signOut(); ok(null) }

    fun deleteCloudData() = busy {
        when (val r = sync.deleteCloudData()) {
            is AppResult.Success -> ok(UiText.Res(R.string.cloud_deleted))
            is AppResult.Failure -> fail(UiText.Error(r.error))
        }
    }

    fun deleteAccount() = busy {
        when (val r = sync.deleteAccount()) {
            is AppResult.Success -> ok(UiText.Res(R.string.cloud_deleted))
            is AppResult.Failure -> fail(UiText.Error(r.error))
        }
    }

    fun dismissMessage() = local.update { it.copy(message = null) }

    private fun ok(message: UiText?) = local.update { it.copy(message = message, isError = false) }
    private fun fail(message: UiText) = local.update { it.copy(message = message, isError = true) }

    private fun busy(block: suspend () -> Unit) {
        if (local.value.busy) return
        local.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try { block() } finally { local.update { it.copy(busy = false) } }
        }
    }
}
