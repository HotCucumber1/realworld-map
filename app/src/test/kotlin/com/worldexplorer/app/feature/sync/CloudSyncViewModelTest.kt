package com.worldexplorer.app.feature.sync

import com.worldexplorer.app.MainDispatcherRule
import com.worldexplorer.app.R
import com.worldexplorer.app.ui.common.UiText
import com.worldexplorer.domain.fakes.FakeConnectivity
import com.worldexplorer.domain.fakes.FakeSettingsRepository
import com.worldexplorer.domain.fakes.FakeSyncScheduler
import com.worldexplorer.domain.repository.SyncRepository
import com.worldexplorer.domain.result.AppError
import com.worldexplorer.domain.result.AppResult
import com.worldexplorer.domain.sync.SignUpOutcome
import com.worldexplorer.domain.sync.SyncReport
import com.worldexplorer.domain.sync.SyncStatus
import com.worldexplorer.domain.usecase.ObserveSettingsUseCase
import com.worldexplorer.domain.usecase.SyncDataUseCase
import com.worldexplorer.domain.usecase.UpdateSettingsUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CloudSyncViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val settings = FakeSettingsRepository()
    private val scheduler = FakeSyncScheduler()
    private val repo = mockk<SyncRepository>(relaxed = true) {
        every { isConfigured } returns true
        every { observeStatus() } returns flowOf(SyncStatus(true, null, false, null, null, null, 0, 0, 0))
    }
    // Created lazily: the Main dispatcher rule must be active before viewModelScope is touched.
    private val vm by lazy { CloudSyncViewModel(repo, SyncDataUseCase(repo, settings, FakeConnectivity()), ObserveSettingsUseCase(settings), UpdateSettingsUseCase(settings, scheduler)) }

    @Test
    fun `wrong password is shown as an error`() {
        coEvery { repo.signIn(any(), any()) } returns AppResult.Failure(AppError.InvalidCredentials("Invalid login credentials"))
        vm.signIn("a@example.com", "wrong1")
        val s = vm.stateValue()
        assertTrue(s.isError)
        assertEquals(UiText.Error(AppError.InvalidCredentials("Invalid login credentials")), s.message)
        assertFalse(s.busy)
    }

    @Test
    fun `sign up that needs confirmation tells the user to check e-mail`() {
        coEvery { repo.signUp(any(), any()) } returns AppResult.Success(SignUpOutcome.CONFIRMATION_REQUIRED)
        vm.signUp("a@example.com", "secret1")
        assertEquals(UiText.Res(R.string.cloud_confirm_email), vm.stateValue().message)
    }

    @Test
    fun `enabling sync is the user's consent and triggers the first sync`() {
        coEvery { repo.sync() } returns AppResult.Success(SyncReport(1, 10, 50, 0, 0, 0))
        vm.setSyncEnabled(true)
        assertTrue(settings.state.value.syncEnabled)
        assertEquals(true, scheduler.periodic)
        coVerify(exactly = 1) { repo.sync() }
        assertEquals(UiText.Res(R.string.cloud_sync_done, listOf(10, 0)), vm.stateValue().message)
    }

    @Test
    fun `sync without consent does not upload`() {
        vm.syncNow()
        coVerify(exactly = 0) { repo.sync() }
        assertTrue(vm.stateValue().isError)
    }

    @Test
    fun `backend outage is reported and nothing crashes`() {
        settings.state.value = settings.state.value.copy(syncEnabled = true)
        coEvery { repo.sync() } returns AppResult.Failure(AppError.BackendUnavailable("HTTP 503"))
        vm.syncNow()
        assertEquals(UiText.Error(AppError.BackendUnavailable("HTTP 503")), vm.stateValue().message)
    }

    /** state is WhileSubscribed: read the combined value through a short (eager) collection. */
    private fun CloudSyncViewModel.stateValue(): CloudUiState {
        var value: CloudUiState? = null
        val job = CoroutineScope(main.dispatcher).launch { state.collect { value = it } }
        job.cancel()
        return value!!
    }
}
