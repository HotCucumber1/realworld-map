package com.worldexplorer.domain.repository

import com.worldexplorer.domain.backup.BackupInfo
import com.worldexplorer.domain.backup.ImportMode
import com.worldexplorer.domain.backup.ImportSummary
import com.worldexplorer.domain.result.AppResult

/** Document locations are opaque strings (content:// URIs on Android). */
interface BackupRepository {
    suspend fun export(destination: String): AppResult<BackupInfo>
    suspend fun inspect(source: String): AppResult<BackupInfo>
    suspend fun import(source: String, mode: ImportMode): AppResult<ImportSummary>
}
