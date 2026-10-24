package com.worldexplorer.domain.repository

import com.worldexplorer.domain.result.AppResult
import com.worldexplorer.domain.sync.SignUpOutcome
import com.worldexplorer.domain.sync.SyncReport
import com.worldexplorer.domain.sync.SyncStatus
import kotlinx.coroutines.flow.Flow

/** Cloud synchronization; the concrete backend lives behind this interface. */
interface SyncRepository {
    val isConfigured: Boolean
    fun observeStatus(): Flow<SyncStatus>

    suspend fun signIn(email: String, password: String): AppResult<Unit>
    suspend fun signUp(email: String, password: String): AppResult<SignUpOutcome>
    suspend fun signOut()

    /** One full incremental round: push local changes, then pull remote changes. */
    suspend fun sync(): AppResult<SyncReport>

    /** Deletes all of the user's rows in the cloud; local data is kept and marked as not uploaded. */
    suspend fun deleteCloudData(): AppResult<Unit>

    /** Deletes cloud data and the account itself, then signs out. */
    suspend fun deleteAccount(): AppResult<Unit>
}

/** Background scheduling of sync (WorkManager on Android). */
interface SyncScheduler {
    fun schedulePeriodic(unmeteredOnly: Boolean)
    fun cancelPeriodic()
    fun requestSyncNow()
}
