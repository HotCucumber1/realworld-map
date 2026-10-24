package com.worldexplorer.data.backup

import android.content.Context
import android.util.Log
import com.worldexplorer.data.database.WorldExplorerDatabase
import com.worldexplorer.domain.AppDispatchers
import com.worldexplorer.domain.backup.BackupInfo
import com.worldexplorer.domain.backup.ImportMode
import com.worldexplorer.domain.backup.ImportSummary
import com.worldexplorer.domain.model.ExploreRadius
import com.worldexplorer.domain.model.FogStyle
import com.worldexplorer.domain.model.MapStyle
import com.worldexplorer.domain.model.TrackingProfile
import com.worldexplorer.domain.repository.BackupRepository
import com.worldexplorer.domain.repository.SettingsRepository
import com.worldexplorer.domain.result.AppError
import com.worldexplorer.domain.result.AppResult
import com.worldexplorer.domain.tracking.Clock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: WorldExplorerDatabase,
    private val streams: DocumentStreams,
    private val writer: BackupWriter,
    private val reader: BackupReader,
    private val importer: BackupImporter,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val buildInfo: AppBuildInfo,
    private val dispatchers: AppDispatchers,
) : BackupRepository {
    private val mutex = Mutex()

    override suspend fun export(destination: String): AppResult<BackupInfo> = run("export") {
        val info = streams.openOutput(destination).use { out ->
            writer.write(out, settings.current(), buildInfo.versionName, clock.nowUtcMillis())
        }
        AppResult.Success(info)
    }

    override suspend fun inspect(source: String): AppResult<BackupInfo> = run("inspect") {
        withStagedCopy(source) { file -> AppResult.Success(reader.validate(file).toInfo()) }
    }

    override suspend fun import(source: String, mode: ImportMode): AppResult<ImportSummary> = run("import") {
        withStagedCopy(source) { file ->
            val backup = reader.validate(file)
            val safety = if (mode == ImportMode.REPLACE) createSafetyBackup() else null
            val counters = importer.apply(backup, mode, clock.nowUtcMillis())
            // Settings are applied only when replacing: merging keeps this phone's preferences.
            val settingsApplied = mode == ImportMode.REPLACE
            if (settingsApplied) applySettings(backup.settings)
            AppResult.Success(counters.toSummary(mode, settingsApplied, safety?.absolutePath))
        }
    }

    /**
     * Before REPLACE the current local data is exported into app-private storage
     * (files/backups, the 3 newest are kept). Skipped when there is nothing to lose.
     */
    private suspend fun createSafetyBackup(): File? {
        if (db.trackDao().count() == 0 && db.cellDao().count() == 0L) return null
        val dir = File(context.filesDir, "backups").apply { mkdirs() }
        val file = File(dir, "before-replace-${clock.nowUtcMillis()}.zip")
        file.outputStream().use { writer.write(it, settings.current(), buildInfo.versionName, clock.nowUtcMillis()) }
        dir.listFiles { f -> f.name.startsWith("before-replace-") }?.sortedByDescending { it.name }?.drop(KEEP_SAFETY_BACKUPS)?.forEach { it.delete() }
        return file
    }

    private suspend fun applySettings(s: BackupSettings) {
        settings.update { current ->
            current.copy(
                mapMode = enumOr(s.mapMode, current.mapMode),
                exploreRadiusMeters = ExploreRadius.sanitize(s.exploreRadiusMeters),
                trackingProfile = enumOr<TrackingProfile>(s.trackingProfile, current.trackingProfile),
                showTrack = s.showTrack,
                showExploredHighlight = s.showExploredHighlight,
                fogStyle = enumOr<FogStyle>(s.fogStyle, current.fogStyle),
                mapStyle = enumOr<MapStyle>(s.mapStyle, current.mapStyle),
                customStyleUrl = s.customStyleUrl,
            )
        }
    }

    /** Copies the document into the cache with a size limit so it can be read twice (validate, apply). */
    private suspend fun <T> withStagedCopy(source: String, block: suspend (File) -> AppResult<T>): AppResult<T> {
        val dir = File(context.cacheDir, "import").apply { mkdirs() }
        val staged = File.createTempFile("backup-", ".zip", dir)
        try {
            streams.openInput(source).use { copyLimited(it, staged) }
            return block(staged)
        } finally {
            staged.delete()
        }
    }

    private fun copyLimited(input: InputStream, target: File) {
        target.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                total += n
                if (total > BackupFormat.MAX_ARCHIVE_BYTES) throw BackupException(AppError.BackupTooLarge(BackupFormat.MAX_ARCHIVE_BYTES))
                out.write(buf, 0, n)
            }
        }
    }

    private suspend fun <T> run(operation: String, block: suspend () -> AppResult<T>): AppResult<T> = mutex.withLock {
        withContext(dispatchers.io) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupException) {
                Log.w(TAG, "$operation rejected: ${e.error}")
                AppResult.Failure(e.error)
            } catch (e: IOException) {
                Log.e(TAG, "$operation failed", e)
                val full = e.message?.contains("ENOSPC") == true || e.message?.contains("No space", ignoreCase = true) == true
                AppResult.Failure(if (full) AppError.StorageFull else AppError.FileAccess(e.javaClass.simpleName))
            } catch (e: SecurityException) {
                Log.e(TAG, "$operation: no access to document", e)
                AppResult.Failure(AppError.FileAccess("permission denied"))
            } catch (e: android.database.sqlite.SQLiteFullException) {
                Log.e(TAG, "$operation: storage full", e)
                AppResult.Failure(AppError.StorageFull)
            } catch (e: android.database.sqlite.SQLiteException) {
                Log.e(TAG, "$operation: database error", e)
                AppResult.Failure(AppError.Database(e.javaClass.simpleName))
            }
        }
    }

    private fun ValidatedBackup.toInfo() = BackupInfo(
        formatVersion = manifest.formatVersion,
        schemaVersion = manifest.schemaVersion,
        appVersion = manifest.appVersion,
        createdAtUtc = manifest.createdAtUtc,
        trackCount = manifest.trackCount,
        pointCount = manifest.pointCount,
        cellCount = manifest.cellCount,
        includesSettings = true,
    )

    private companion object {
        const val TAG = "WorldExplorer"
        const val KEEP_SAFETY_BACKUPS = 3

        inline fun <reified E : Enum<E>> enumOr(value: String, default: E): E = enumValues<E>().firstOrNull { it.name == value } ?: default
    }
}

