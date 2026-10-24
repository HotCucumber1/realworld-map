package com.worldexplorer.app.feature.backup

import com.worldexplorer.app.MainDispatcherRule
import com.worldexplorer.app.ui.common.UiText
import com.worldexplorer.domain.backup.BackupInfo
import com.worldexplorer.domain.backup.ImportMode
import com.worldexplorer.domain.backup.ImportSummary
import com.worldexplorer.domain.fakes.FakeTrackingController
import com.worldexplorer.domain.repository.BackupRepository
import com.worldexplorer.domain.result.AppError
import com.worldexplorer.domain.result.AppResult
import com.worldexplorer.domain.usecase.ExportBackupUseCase
import com.worldexplorer.domain.usecase.ImportBackupUseCase
import com.worldexplorer.domain.usecase.InspectBackupUseCase
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BackupViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val info = BackupInfo(1, 1, "0.1.0", 1_700_000_000_000, 3, 120, 900, true)
    private fun summary(mode: ImportMode) = ImportSummary(mode, 3, 0, 0, 120, 0, 900, 0, mode == ImportMode.REPLACE, null)

    private class FakeBackup(val info: BackupInfo) : BackupRepository {
        var inspectResult: AppResult<BackupInfo> = AppResult.Success(info)
        val imports = mutableListOf<ImportMode>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun export(destination: String): AppResult<BackupInfo> = AppResult.Success(info)
        override suspend fun inspect(source: String) = inspectResult
        override suspend fun import(source: String, mode: ImportMode): AppResult<ImportSummary> {
            gate?.await()
            imports += mode
            return AppResult.Success(ImportSummary(mode, 3, 0, 0, 120, 0, 900, 0, mode == ImportMode.REPLACE, null))
        }
    }

    private val backup = FakeBackup(info)
    private val controller = FakeTrackingController()
    // Created lazily: the Main dispatcher rule must be active before viewModelScope is touched.
    private val vm by lazy { BackupViewModel(ExportBackupUseCase(backup), InspectBackupUseCase(backup), ImportBackupUseCase(backup, controller)) }

    @Test
    fun `export reports what was written`() {
        vm.onExportTo("content://doc")
        assertEquals(info, vm.state.value.exported)
        assertFalse(vm.state.value.working)
    }

    @Test
    fun `import first validates and waits for the user's choice`() {
        vm.onImportFrom("content://doc")
        assertEquals(PendingImport("content://doc", info), vm.state.value.pending)
        assertTrue("nothing imported before the choice", backup.imports.isEmpty())
    }

    @Test
    fun `merge imports immediately after the choice`() {
        vm.onImportFrom("content://doc")
        vm.onChooseMerge()
        assertEquals(listOf(ImportMode.MERGE), backup.imports)
        assertEquals(summary(ImportMode.MERGE), vm.state.value.imported)
        assertNull(vm.state.value.pending)
    }

    @Test
    fun `replace requires an explicit second confirmation`() {
        vm.onImportFrom("content://doc")
        vm.onChooseReplace()
        assertTrue(vm.state.value.confirmReplace)
        assertTrue(backup.imports.isEmpty())
        vm.onConfirmReplace()
        assertEquals(listOf(ImportMode.REPLACE), backup.imports)
    }

    @Test
    fun `cancel leaves data untouched`() {
        vm.onImportFrom("content://doc")
        vm.onChooseReplace()
        vm.onCancelImport()
        vm.onConfirmReplace()
        assertTrue(backup.imports.isEmpty())
    }

    @Test
    fun `corrupted file shows a typed error`() {
        backup.inspectResult = AppResult.Failure(AppError.CorruptedBackup("checksum mismatch in points.jsonl"))
        vm.onImportFrom("content://doc")
        assertEquals(UiText.Error(AppError.CorruptedBackup("checksum mismatch in points.jsonl")), vm.state.value.error)
        assertNull(vm.state.value.pending)
    }

    @Test
    fun `import while recording is refused`() {
        controller.isRecorderRunning = true
        vm.onImportFrom("content://doc")
        vm.onChooseMerge()
        assertEquals(UiText.Res(com.worldexplorer.app.R.string.backup_import_while_tracking), vm.state.value.error)
        assertTrue(backup.imports.isEmpty())
    }

    @Test
    fun `operation in progress blocks a second one`() {
        backup.gate = CompletableDeferred()
        vm.onImportFrom("content://doc")
        vm.onChooseMerge()
        assertTrue(vm.state.value.working)
        vm.onExportTo("content://other")
        assertNull(vm.state.value.exported)
        backup.gate!!.complete(Unit)
        assertFalse(vm.state.value.working)
    }
}
