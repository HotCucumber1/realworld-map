package com.worldexplorer.domain.usecase

import com.worldexplorer.domain.repository.ConnectivityRepository
import com.worldexplorer.domain.repository.SettingsRepository
import com.worldexplorer.domain.repository.SyncRepository
import com.worldexplorer.domain.result.AppError
import com.worldexplorer.domain.result.AppResult
import com.worldexplorer.domain.sync.SyncReport
import javax.inject.Inject

/**
 * Runs one sync round if the user enabled sync. Location data never leaves the device otherwise.
 */
class SyncDataUseCase @Inject constructor(
    private val sync: SyncRepository,
    private val settings: SettingsRepository,
    private val connectivity: ConnectivityRepository,
) {
    suspend operator fun invoke(): AppResult<SyncReport> {
        if (!sync.isConfigured) return AppResult.Failure(AppError.SyncNotConfigured)
        if (!settings.current().syncEnabled) return AppResult.Failure(AppError.Validation("sync disabled"))
        if (!connectivity.isOnline()) return AppResult.Failure(AppError.NoNetwork)
        return sync.sync()
    }
}
