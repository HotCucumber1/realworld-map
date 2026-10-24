package com.worldexplorer.data.backup

import com.worldexplorer.domain.geo.ExplorationGrid
import com.worldexplorer.domain.geo.GeoMath
import com.worldexplorer.domain.model.ExploreRadius
import com.worldexplorer.domain.model.TrackStatus
import com.worldexplorer.domain.result.AppError
import kotlinx.serialization.SerializationException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.inject.Inject

class BackupException(val error: AppError, cause: Throwable? = null) : Exception(error.toString(), cause)

/** A backup that passed full validation; [file] stays open for the apply phase. */
class ValidatedBackup(
    val file: File,
    val manifest: BackupManifest,
    val settings: BackupSettings,
    val trackIds: Set<String>,
)

/**
 * Validates a backup completely before anything is written: structure, versions, checksums,
 * counts, every record's fields, coordinates and track references. Reading is streamed with hard
 * size limits, so a corrupted or hostile archive cannot exhaust memory.
 */
class BackupReader @Inject constructor() {
    private val json = BackupFormat.json

    fun validate(file: File): ValidatedBackup = guard {
        ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            names.firstOrNull { it !in BackupFormat.ALLOWED_ENTRIES }?.let { corrupt("unexpected entry '$it'") }
            if (names.size != names.toSet().size) corrupt("duplicate entries")
            val manifestEntry = zip.getEntry(BackupFormat.MANIFEST) ?: corrupt("manifest.json is missing")
            val manifestText = zip.getInputStream(manifestEntry).use { readLimited(it, BackupFormat.MAX_MANIFEST_BYTES) }
            val manifest = decode<BackupManifest>(manifestText, "manifest")
            if (manifest.format != BackupFormat.FORMAT_ID) corrupt("not a World Explorer backup")
            if (manifest.formatVersion > BackupFormat.FORMAT_VERSION || manifest.formatVersion < 1) {
                throw BackupException(AppError.UnsupportedBackupVersion(manifest.formatVersion, BackupFormat.FORMAT_VERSION))
            }
            for (name in BackupFormat.DATA_ENTRIES) {
                if (zip.getEntry(name) == null) corrupt("$name is missing")
                if (manifest.entries[name] == null) corrupt("manifest has no checksum for $name")
            }

            var settings: BackupSettings? = null
            readEntry(zip, BackupFormat.SETTINGS, manifest, wholeFile = true) { text ->
                settings = decode<BackupSettings>(text, "settings").also(::validateSettings)
            }

            val trackIds = HashSet<String>()
            readEntry(zip, BackupFormat.TRACKS, manifest) { line ->
                val t = decode<BackupTrack>(line, "track")
                validateTrack(t)
                if (!trackIds.add(t.id)) corrupt("duplicate track id")
                if (trackIds.size > BackupFormat.MAX_TRACKS) corrupt("too many tracks")
            }
            var points = 0L
            readEntry(zip, BackupFormat.POINTS, manifest) { line ->
                val p = decode<BackupPoint>(line, "point")
                validatePoint(p)
                if (p.trackId !in trackIds) corrupt("point references a missing track")
                points++
            }
            var cells = 0L
            readEntry(zip, BackupFormat.CELLS, manifest) { line ->
                validateCell(decode<BackupCell>(line, "cell"))
                cells++
            }
            if (trackIds.size != manifest.trackCount || points != manifest.pointCount || cells != manifest.cellCount) {
                corrupt("record counts do not match the manifest")
            }
            ValidatedBackup(file, manifest, settings ?: corrupt("settings missing"), trackIds)
        }
    }

    /**
     * Apply phase: lazily iterates the records of an already validated entry. The iterator is
     * consumed by the caller (which may suspend between records), so memory stays bounded.
     */
    suspend fun <T> withRecords(backup: ValidatedBackup, name: String, parse: (String) -> T, block: suspend (Iterator<T>) -> Unit) {
        ZipFile(backup.file).use { zip ->
            val entry = zip.getEntry(name) ?: corrupt("$name is missing")
            zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { r ->
                val lines = r.lineSequence().filter { it.isNotEmpty() }.map(parse).iterator()
                block(lines)
            }
        }
    }

    fun parseTrack(line: String): BackupTrack = decode(line, "track")
    fun parsePoint(line: String): BackupPoint = decode(line, "point")
    fun parseCell(line: String): BackupCell = decode(line, "cell")

    /**
     * Streams an entry line by line (or as a whole file), enforcing size limits and verifying its
     * SHA-256 and line count against the manifest.
     */
    private fun readEntry(zip: ZipFile, name: String, manifest: BackupManifest, wholeFile: Boolean = false, block: (String) -> Unit) {
        val expected = manifest.entries[name] ?: corrupt("manifest has no checksum for $name")
        val entry = zip.getEntry(name) ?: corrupt("$name is missing")
        val digest = MessageDigest.getInstance("SHA-256")
        var lines = 0L
        zip.getInputStream(entry).use { raw ->
            val input = LimitedDigestStream(raw, BackupFormat.MAX_ENTRY_BYTES, digest)
            if (wholeFile) {
                block(readLimited(input, BackupFormat.MAX_MANIFEST_BYTES))
            } else {
                val buffered = input.buffered(64 * 1024)
                val line = ByteArrayOutputStream(256)
                while (true) {
                    val b = buffered.read()
                    if (b == -1) break
                    if (b == '\n'.code) {
                        lines++
                        if (line.size() > 0) block(line.toString(Charsets.UTF_8.name()))
                        line.reset()
                    } else {
                        line.write(b)
                        if (line.size() > BackupFormat.MAX_LINE_BYTES) corrupt("record too long in $name")
                    }
                }
                if (line.size() > 0) corrupt("$name is truncated")
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (hash != expected.sha256) corrupt("checksum mismatch in $name")
            if (input.count != expected.bytes || (!wholeFile && lines != expected.lines)) corrupt("size mismatch in $name")
        }
    }

    private inline fun <reified T> decode(text: String, what: String): T = try {
        json.decodeFromString<T>(text)
    } catch (e: SerializationException) {
        throw BackupException(AppError.CorruptedBackup("invalid $what record"), e)
    } catch (e: IllegalArgumentException) {
        throw BackupException(AppError.CorruptedBackup("invalid $what record"), e)
    }

    private fun validateSettings(s: BackupSettings) {
        if (s.exploreRadiusMeters !in ExploreRadius.OPTIONS) corrupt("invalid exploration radius")
        if (s.customStyleUrl.length > 2048) corrupt("custom style URL too long")
    }

    private fun validateTrack(t: BackupTrack) {
        if (!isValidId(t.id)) corrupt("invalid track id")
        if (TrackStatus.entries.none { it.name == t.status }) corrupt("invalid track status")
        if (t.startedAtUtc <= 0 || t.createdAtUtc <= 0 || t.updatedAtUtc <= 0) corrupt("invalid track timestamps")
        if (t.endedAtUtc != null && t.endedAtUtc < t.startedAtUtc) corrupt("track ends before it starts")
        if (!t.distanceMeters.isFinite() || t.distanceMeters < 0 || t.pointCount < 0 || t.rejectedPointCount < 0) corrupt("invalid track statistics")
    }

    private fun validatePoint(p: BackupPoint) {
        if (!isValidId(p.id) || !isValidId(p.trackId)) corrupt("invalid point id")
        if (!GeoMath.isValidCoordinate(p.latitude, p.longitude)) corrupt("invalid coordinates")
        if (p.timestampUtc <= 0) corrupt("invalid point timestamp")
        if (!p.accuracyMeters.isFinite() || p.accuracyMeters < 0) corrupt("invalid accuracy")
        if (p.exploreRadiusMeters !in 1..1_000) corrupt("invalid point radius")
        if (p.altitudeMeters?.isFinite() == false || p.speedMetersPerSecond?.isFinite() == false || p.bearingDegrees?.isFinite() == false) corrupt("invalid optional values")
    }

    private fun validateCell(c: BackupCell) {
        if (c.row !in 0 until ExplorationGrid.ROW_COUNT || c.col !in 0 until ExplorationGrid.COLUMN_COUNT) corrupt("cell out of range")
        if (c.firstVisitedAtUtc < 0) corrupt("invalid cell timestamp")
    }

    private fun isValidId(id: String) = id.isNotBlank() && id.length <= 64 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun readLimited(input: InputStream, limit: Long): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n == -1) break
            total += n
            if (total > limit) corrupt("entry too large")
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: BackupException) {
        throw e
    } catch (e: ZipException) {
        throw BackupException(AppError.CorruptedBackup("not a valid ZIP archive"), e)
    } catch (e: IOException) {
        throw BackupException(AppError.CorruptedBackup("archive cannot be read"), e)
    }

    private fun corrupt(reason: String): Nothing = throw BackupException(AppError.CorruptedBackup(reason))

    /** Counts and hashes bytes; fails once more than [limit] uncompressed bytes were read (zip bomb guard). */
    private class LimitedDigestStream(input: InputStream, private val limit: Long, private val digest: MessageDigest) : FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int {
            val b = super.read()
            if (b != -1) { digest.update(b.toByte()); bump(1) }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) { digest.update(b, off, n); bump(n) }
            return n
        }

        private fun bump(n: Int) {
            count += n
            if (count > limit) throw BackupException(AppError.BackupTooLarge(limit))
        }
    }
}
