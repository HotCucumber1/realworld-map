package com.worldexplorer.domain.usecase

import com.worldexplorer.domain.backup.BackupInfo
import com.worldexplorer.domain.backup.ImportMode
import com.worldexplorer.domain.backup.ImportSummary
import com.worldexplorer.domain.repository.BackupRepository
import com.worldexplorer.domain.repository.TrackingController
import com.worldexplorer.domain.result.AppError
import com.worldexplorer.domain.result.AppResult
import javax.inject.Inject

class ExportBackupUseCase @Inject constructor(private val backup: BackupRepository) {
    suspend operator fun invoke(destination: String): AppResult<BackupInfo> = backup.export(destination)
}

/** Reads and fully validates a backup without changing anything, so the user can confirm. */
class InspectBackupUseCase @Inject constructor(private val backup: BackupRepository) {
    suspend operator fun invoke(source: String): AppResult<BackupInfo> = backup.inspect(source)
}

class ImportBackupUseCase @Inject constructor(
    private val backup: BackupRepository,
    private val controller: TrackingController,
) {
    /** Import is refused while recording: replacing data under an active track would corrupt it. */
    suspend operator fun invoke(source: String, mode: ImportMode): AppResult<ImportSummary> {
        if (controller.isRecorderRunning) return AppResult.Failure(AppError.AlreadyTracking)
        return backup.import(source, mode)
    }
}
