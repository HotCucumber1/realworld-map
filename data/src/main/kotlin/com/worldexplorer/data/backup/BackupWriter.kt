package com.worldexplorer.data.backup

import com.worldexplorer.data.database.LocationPointEntity
import com.worldexplorer.data.database.TrackEntity
import com.worldexplorer.data.database.WorldExplorerDatabase
import com.worldexplorer.domain.backup.BackupInfo
import com.worldexplorer.domain.model.AppSettings
import kotlinx.serialization.encodeToString
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

/** Streams local data into a backup archive without loading points or cells into memory at once. */
class BackupWriter @Inject constructor(private val db: WorldExplorerDatabase) {

    suspend fun write(output: OutputStream, settings: AppSettings, appVersion: String, createdAtUtc: Long): BackupInfo {
        val json = BackupFormat.json
        val entries = LinkedHashMap<String, BackupEntryInfo>()
        val zip = ZipOutputStream(output)

        suspend fun entry(name: String, body: suspend (LineSink) -> Unit) {
            zip.putNextEntry(ZipEntry(name))
            val sink = LineSink(zip)
            body(sink)
            sink.flush()
            zip.closeEntry()
            entries[name] = BackupEntryInfo(sink.sha256(), sink.bytes, sink.lines)
        }

        entry(BackupFormat.SETTINGS) { it.raw(json.encodeToString(settings.toBackup())) }

        // Tracks first: points are only exported for tracks in this snapshot, so the archive is
        // consistent even if recording adds a point during export.
        val tracks = db.trackDao().getAll()
        val trackIds = tracks.mapTo(HashSet()) { it.id }
        entry(BackupFormat.TRACKS) { sink -> tracks.forEach { sink.line(json.encodeToString(it.toBackup())) } }

        var pointCount = 0L
        entry(BackupFormat.POINTS) { sink ->
            var after = ""
            while (true) {
                val page = db.pointDao().pageAfter(after, PAGE)
                if (page.isEmpty()) break
                page.forEach { p ->
                    if (p.trackId in trackIds) {
                        sink.line(json.encodeToString(p.toBackup()))
                        pointCount++
                    }
                }
                after = page.last().id
            }
        }

        var cellCount = 0L
        entry(BackupFormat.CELLS) { sink ->
            var row = -1
            var col = -1
            while (true) {
                val page = db.cellDao().pageAfter(row, col, PAGE)
                if (page.isEmpty()) break
                page.forEach { sink.line(json.encodeToString(BackupCell(it.row, it.column, it.firstVisitedAt))); cellCount++ }
                row = page.last().row
                col = page.last().column
            }
        }

        val manifest = BackupManifest(
            format = BackupFormat.FORMAT_ID,
            formatVersion = BackupFormat.FORMAT_VERSION,
            schemaVersion = WorldExplorerDatabase.VERSION,
            appVersion = appVersion,
            createdAtUtc = createdAtUtc,
            trackCount = tracks.size,
            pointCount = pointCount,
            cellCount = cellCount,
            entries = entries,
        )
        zip.putNextEntry(ZipEntry(BackupFormat.MANIFEST))
        zip.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        zip.finish()
        zip.flush()
        return BackupInfo(
            formatVersion = manifest.formatVersion,
            schemaVersion = manifest.schemaVersion,
            appVersion = appVersion,
            createdAtUtc = createdAtUtc,
            trackCount = tracks.size,
            pointCount = pointCount,
            cellCount = cellCount,
            includesSettings = true,
        )
    }

    /** Writes UTF-8 lines while counting bytes/lines and hashing; never closes the zip stream. */
    class LineSink(target: OutputStream) {
        private val digest = MessageDigest.getInstance("SHA-256")
        private val buffer = target.buffered(64 * 1024)
        var bytes = 0L
            private set
        var lines = 0L
            private set

        fun line(text: String) {
            raw(text)
            writeBytes("\n".toByteArray())
            lines++
        }

        fun raw(text: String) = writeBytes(text.toByteArray(Charsets.UTF_8))

        private fun writeBytes(b: ByteArray) {
            buffer.write(b)
            digest.update(b)
            bytes += b.size
        }

        fun flush() = buffer.flush()
        fun sha256(): String = digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val PAGE = 2_000
    }
}

fun AppSettings.toBackup() = BackupSettings(
    mapMode = mapMode.name,
    exploreRadiusMeters = exploreRadiusMeters,
    trackingProfile = trackingProfile.name,
    showTrack = showTrack,
    showExploredHighlight = showExploredHighlight,
    fogStyle = fogStyle.name,
    mapStyle = mapStyle.name,
    customStyleUrl = customStyleUrl,
)

fun TrackEntity.toBackup() = BackupTrack(
    id = id,
    startedAtUtc = startedAt,
    endedAtUtc = endedAt,
    status = status,
    distanceMeters = distanceMeters,
    pointCount = pointCount,
    rejectedPointCount = rejectedPointCount,
    lastPointAtUtc = lastPointAt,
    createdAtUtc = createdAt,
    updatedAtUtc = updatedAt,
)

fun LocationPointEntity.toBackup() = BackupPoint(
    id = id,
    trackId = trackId,
    latitude = latitude,
    longitude = longitude,
    timestampUtc = timestampUtc,
    accuracyMeters = accuracyMeters,
    altitudeMeters = altitudeMeters,
    speedMetersPerSecond = speedMetersPerSecond,
    bearingDegrees = bearingDegrees,
    source = source,
    exploreRadiusMeters = exploreRadiusMeters,
)

