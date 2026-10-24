package com.worldexplorer.app.feature.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.worldexplorer.app.R
import com.worldexplorer.app.ui.common.UiText
import com.worldexplorer.domain.backup.BackupInfo
import com.worldexplorer.domain.backup.ImportMode
import com.worldexplorer.domain.backup.ImportSummary
import com.worldexplorer.domain.result.AppError
import com.worldexplorer.domain.result.AppResult
import com.worldexplorer.domain.usecase.ExportBackupUseCase
import com.worldexplorer.domain.usecase.ImportBackupUseCase
import com.worldexplorer.domain.usecase.InspectBackupUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BackupUiState(
    val working: Boolean = false,
    /** A validated backup waiting for the user to choose merge or replace. */
    val pending: PendingImport? = null,
    /** REPLACE was chosen and needs explicit confirmation. */
    val confirmReplace: Boolean = false,
    val exported: BackupInfo? = null,
    val imported: ImportSummary? = null,
    val error: UiText? = null,
)

data class PendingImport(val source: String, val info: BackupInfo)

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val export: ExportBackupUseCase,
    private val inspect: InspectBackupUseCase,
    private val import: ImportBackupUseCase,
) : ViewModel() {
    private val mutable = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = mutable.asStateFlow()

    fun onExportTo(destination: String) = run {
        when (val r = export(destination)) {
            is AppResult.Success -> mutable.update { it.copy(exported = r.value) }
            is AppResult.Failure -> mutable.update { it.copy(error = UiText.Error(r.error)) }
        }
    }

    /** First step of import: validate the file and show what it contains. Nothing is changed yet. */
    fun onImportFrom(source: String) = run {
        when (val r = inspect(source)) {
            is AppResult.Success -> mutable.update { it.copy(pending = PendingImport(source, r.value)) }
            is AppResult.Failure -> mutable.update { it.copy(error = UiText.Error(r.error)) }
        }
    }

    fun onChooseMerge() = apply(ImportMode.MERGE)

    fun onChooseReplace() = mutable.update { it.copy(confirmReplace = true) }

    fun onConfirmReplace() = apply(ImportMode.REPLACE)

    fun onCancelImport() = mutable.update { it.copy(pending = null, confirmReplace = false) }

    fun onDismissResult() = mutable.update { it.copy(exported = null, imported = null, error = null) }

    private fun apply(mode: ImportMode) {
        val pending = mutable.value.pending ?: return
        mutable.update { it.copy(pending = null, confirmReplace = false) }
        run {
            when (val r = import(pending.source, mode)) {
                is AppResult.Success -> mutable.update { it.copy(imported = r.value) }
                is AppResult.Failure -> mutable.update {
                    it.copy(error = if (r.error == AppError.AlreadyTracking) UiText.Res(R.string.backup_import_while_tracking) else UiText.Error(r.error))
                }
            }
        }
    }

    private fun run(block: suspend () -> Unit) {
        if (mutable.value.working) return
        mutable.update { it.copy(working = true, error = null, exported = null, imported = null) }
        viewModelScope.launch {
            try {
                block()
            } finally {
                mutable.update { it.copy(working = false) }
            }
        }
    }
}
