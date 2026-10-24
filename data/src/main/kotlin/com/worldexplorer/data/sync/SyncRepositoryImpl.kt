package com.worldexplorer.data.sync

import android.util.Log
import androidx.room.withTransaction
import com.worldexplorer.data.database.SyncMetadataEntity
import com.worldexplorer.data.database.WorldExplorerDatabase
import com.worldexplorer.domain.AppDispatchers
import com.worldexplorer.domain.repository.SettingsRepository
import com.worldexplorer.domain.repository.SyncRepository
import com.worldexplorer.domain.repository.SyncScheduler
import com.worldexplorer.domain.result.AppError
import com.worldexplorer.domain.result.AppResult
import com.worldexplorer.domain.sync.CloudAccount
import com.worldexplorer.domain.sync.SignUpOutcome
import com.worldexplorer.domain.sync.SyncReport
import com.worldexplorer.domain.sync.SyncStatus
import com.worldexplorer.domain.tracking.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.database.sqlite.SQLiteException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncRepositoryImpl @Inject constructor(
    private val config: SupabaseConfig,
    private val auth: AuthBackend,
    private val backend: SyncBackend,
    private val sessions: SessionStore,
    private val engine: SyncEngine,
    private val db: WorldExplorerDatabase,
    private val settings: SettingsRepository,
    private val scheduler: SyncScheduler,
    private val clock: Clock,
    private val dispatchers: AppDispatchers,
) : SyncRepository {
    private val mutex = Mutex()
    private val running = MutableStateFlow(false)

    override val isConfigured: Boolean get() = config.isConfigured

    override fun observeStatus(): Flow<SyncStatus> = combine(
        sessions.session,
        running,
        db.syncMetadataDao().observeAll(),
        db.trackDao().observePendingCount(),
        combine(db.pointDao().observePendingCount(), db.cellDao().observePendingCount()) { p, c -> p to c },
    ) { session, isRunning, meta, pendingTracks, (pendingPoints, pendingCells) ->
        val m = meta.associate { it.key to it.value }
        SyncStatus(
            configured = config.isConfigured,
            account = session?.let { CloudAccount(it.userId, it.email) },
            running = isRunning,
            lastSuccessAtUtc = m[LAST_SUCCESS]?.toLongOrNull(),
            lastAttemptAtUtc = m[LAST_ATTEMPT]?.toLongOrNull(),
            lastError = m[LAST_ERROR]?.takeIf { it.isNotEmpty() },
            pendingTracks = pendingTracks,
            pendingPoints = pendingPoints,
            pendingCells = pendingCells,
        )
    }

    override suspend fun signIn(email: String, password: String): AppResult<Unit> = call("signIn") {
        if (!config.isConfigured) return@call AppResult.Failure(AppError.SyncNotConfigured)
        if (!isPlausibleEmail(email) || password.length < MIN_PASSWORD) return@call AppResult.Failure(AppError.Validation("credentials"))
        onSignedIn(auth.signIn(email.trim(), password))
        AppResult.Success(Unit)
    }

    override suspend fun signUp(email: String, password: String): AppResult<SignUpOutcome> = call("signUp") {
        if (!config.isConfigured) return@call AppResult.Failure(AppError.SyncNotConfigured)
        if (!isPlausibleEmail(email) || password.length < MIN_PASSWORD) return@call AppResult.Failure(AppError.Validation("credentials"))
        when (val r = auth.signUp(email.trim(), password)) {
            is SignUpResult.SignedIn -> { onSignedIn(r.session); AppResult.Success(SignUpOutcome.SIGNED_IN) }
            SignUpResult.ConfirmationRequired -> AppResult.Success(SignUpOutcome.CONFIRMATION_REQUIRED)
        }
    }

    override suspend fun signOut() {
        sessions.session.value?.let { runCatching { auth.signOut(it) } }
        sessions.save(null)
        settings.update { it.copy(syncEnabled = false) }
        scheduler.cancelPeriodic()
    }

    override suspend fun sync(): AppResult<SyncReport> = mutex.withLock {
        val session = sessions.session.value ?: return AppResult.Failure(AppError.NotSignedIn)
        running.value = true
        putMeta(LAST_ATTEMPT, clock.nowUtcMillis().toString())
        try {
            val result = call("sync") { AppResult.Success(engine.run(session.userId, backend)) }
            when (result) {
                is AppResult.Success -> { putMeta(LAST_SUCCESS, clock.nowUtcMillis().toString()); putMeta(LAST_ERROR, "") }
                is AppResult.Failure -> putMeta(LAST_ERROR, describe(result.error))
            }
            result
        } finally {
            running.value = false
        }
    }

    override suspend fun deleteCloudData(): AppResult<Unit> = mutex.withLock {
        val session = sessions.session.value ?: return AppResult.Failure(AppError.NotSignedIn)
        call("deleteCloudData") {
            backend.deleteAllData(session.userId)
            settings.update { it.copy(syncEnabled = false) }
            scheduler.cancelPeriodic()
            resetLocalSyncState()
            AppResult.Success(Unit)
        }
    }

    override suspend fun deleteAccount(): AppResult<Unit> = mutex.withLock {
        sessions.session.value ?: return AppResult.Failure(AppError.NotSignedIn)
        call("deleteAccount") {
            backend.deleteAccount()
            sessions.save(null)
            settings.update { it.copy(syncEnabled = false) }
            scheduler.cancelPeriodic()
            resetLocalSyncState()
            AppResult.Success(Unit)
        }
    }

    /** Local data was uploaded for another account (or deleted remotely): upload it again next time. */
    private suspend fun resetLocalSyncState() = db.withTransaction {
        db.trackDao().markAllPending()
        db.pointDao().markAllPending()
        db.cellDao().markAllPending()
        db.syncMetadataDao().deleteByPrefix(SyncCursors.PREFIX)
    }

    private suspend fun onSignedIn(session: Session) {
        val previous = db.syncMetadataDao().get(LAST_USER)
        if (previous != null && previous != session.userId) resetLocalSyncState()
        sessions.save(session)
        putMeta(LAST_USER, session.userId)
    }

    private suspend fun putMeta(key: String, value: String) = db.syncMetadataDao().put(SyncMetadataEntity(key, value))

    private suspend fun <T> call(operation: String, block: suspend () -> AppResult<T>): AppResult<T> = withContext(dispatchers.io) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncException) {
            Log.w("WorldExplorer", "$operation failed: ${e.error}")
            if (e.error == AppError.SessionExpired) sessions.save(null)
            AppResult.Failure(e.error)
        } catch (e: SQLiteException) {
            Log.e("WorldExplorer", "$operation: database error", e)
            AppResult.Failure(AppError.Database(e.javaClass.simpleName))
        }
    }

    private fun describe(error: AppError): String = when (error) {
        AppError.NoNetwork -> "Нет сети"
        AppError.NotSignedIn -> "Не выполнен вход"
        AppError.SessionExpired -> "Сессия истекла, войдите снова"
        is AppError.BackendUnavailable -> "Сервер недоступен (${error.reason})"
        is AppError.BackendRejected -> "Сервер отклонил запрос (HTTP ${error.httpCode})"
        is AppError.Database -> "Ошибка локальной базы"
        AppError.StorageFull -> "Недостаточно места"
        else -> error.toString()
    }

    private fun isPlausibleEmail(email: String) = EMAIL.matches(email.trim())

    private companion object {
        const val LAST_SUCCESS = "status.last_success"
        const val LAST_ATTEMPT = "status.last_attempt"
        const val LAST_ERROR = "status.last_error"
        const val LAST_USER = "account.last_user"
        const val MIN_PASSWORD = 6
        val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}

/** Used when the app was built without backend credentials: every call reports "not configured". */
class UnconfiguredBackend : CloudBackend {
    private fun fail(): Nothing = throw SyncException(AppError.SyncNotConfigured)
    override suspend fun signIn(email: String, password: String) = fail()
    override suspend fun signUp(email: String, password: String) = fail()
    override suspend fun refresh(session: Session) = fail()
    override suspend fun signOut(session: Session) = Unit
    override suspend fun pushTracks(tracks: List<RemoteTrack>) = fail()
    override suspend fun pushPoints(points: List<RemotePoint>) = fail()
    override suspend fun pushCells(cells: List<RemoteCell>) = fail()
    override suspend fun pullTracks(userId: String, afterSeq: Long, limit: Int) = fail()
    override suspend fun pullPoints(userId: String, afterSeq: Long, limit: Int) = fail()
    override suspend fun pullCells(userId: String, afterSeq: Long, limit: Int) = fail()
    override suspend fun deleteAllData(userId: String) = fail()
    override suspend fun deleteAccount() = fail()
}
