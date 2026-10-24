package com.worldexplorer.data.backup

import com.worldexplorer.data.MutableClock
import com.worldexplorer.data.TestSettings
import com.worldexplorer.data.Walker
import com.worldexplorer.data.context
import com.worldexplorer.data.database.ExplorationStore
import com.worldexplorer.data.database.WorldExplorerDatabase
import com.worldexplorer.data.inMemoryDb
import com.worldexplorer.domain.AppDispatchers
import com.worldexplorer.domain.backup.ImportMode
import com.worldexplorer.domain.model.AppSettings
import com.worldexplorer.domain.model.FogStyle
import com.worldexplorer.domain.model.MapMode
import com.worldexplorer.domain.model.TrackStatus
import com.worldexplorer.domain.result.AppError
import com.worldexplorer.domain.result.AppResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class BackupRoundTripTest {
    @get:Rule val tmp = TemporaryFolder()
    private val clock = MutableClock()
    private val source = inMemoryDb()
    private val target = inMemoryDb()
    private val sourceSettings = TestSettings(AppSettings(mapMode = MapMode.EXPLORER, exploreRadiusMeters = 50, fogStyle = FogStyle.DARK))
    private val targetSettings = TestSettings()

    @After fun close() { source.close(); target.close() }

    private fun repo(db: WorldExplorerDatabase, settings: TestSettings) = BackupRepositoryImpl(
        context(), db, ContentResolverDocumentStreams(context()), BackupWriter(db), BackupReader(), BackupImporter(db, BackupReader(), ExplorationStore(db)),
        settings, clock, AppBuildInfo("test"), AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined),
    )

    private suspend fun populate(db: WorldExplorerDatabase) {
        val w = Walker(db, clock)
        w.walk("a", 48.0, 11.0, points = 12)
        w.walk("b", 48.001, 11.002, points = 8, radius = 50)
        w.walk("c", -33.0, 179.9999, points = 5) // crosses no data line issues: stored as-is
    }

    /** Area is rounded: it is a floating-point sum whose order differs between incremental and bulk insert. */
    private data class Snapshot(val tracks: List<Any>, val points: List<Any>, val cells: List<Any>, val areaMm2: Long, val cellCount: Long)

    private suspend fun snapshot(db: WorldExplorerDatabase): Snapshot {
        val tracks = db.trackDao().getAll().map { it.copy(syncState = 0) }
        val points = tracks.flatMap { db.pointDao().getForTrack(it.id) }.map { it.copy(createdAt = 0, syncState = 0) }.sortedBy { it.id }
        val cells = db.cellDao().pageAfter(-1, -1, Int.MAX_VALUE).map { it.copy(syncState = 0) }
        val stats = db.statsDao().get()!!
        return Snapshot(tracks, points, cells, Math.round(stats.totalAreaSquareMeters * 1_000), stats.cellCount)
    }

    private suspend fun exportFrom(db: WorldExplorerDatabase, settings: TestSettings): File {
        val file = tmp.newFile("backup.zip")
        val r = repo(db, settings).export(file.absolutePath)
        assertTrue("export: $r", r is AppResult.Success)
        return file
    }

    @Test
    fun `export then import into a clean install restores identical data and settings`() = runTest {
        populate(source)
        val file = exportFrom(source, sourceSettings)
        val info = (repo(target, targetSettings).inspect(file.absolutePath) as AppResult.Success).value
        assertEquals(3, info.trackCount)
        assertEquals(25L, info.pointCount)

        val summary = (repo(target, targetSettings).import(file.absolutePath, ImportMode.REPLACE) as AppResult.Success).value
        assertEquals(3, summary.tracksInserted)
        assertEquals(25L, summary.pointsInserted)
        assertNull("nothing to protect on a clean install", summary.safetyBackupPath)
        assertEquals(snapshot(source), snapshot(target))
        assertEquals(MapMode.EXPLORER, targetSettings.state.value.mapMode)
        assertEquals(50, targetSettings.state.value.exploreRadiusMeters)
        assertEquals(FogStyle.DARK, targetSettings.state.value.fogStyle)
    }

    @Test
    fun `repeated merge import is idempotent`() = runTest {
        populate(source)
        val file = exportFrom(source, sourceSettings)
        val r = repo(target, targetSettings)
        r.import(file.absolutePath, ImportMode.MERGE)
        val first = snapshot(target)
        val second = (r.import(file.absolutePath, ImportMode.MERGE) as AppResult.Success).value
        assertEquals(0, second.tracksInserted)
        assertEquals(0L, second.pointsInserted)
        assertEquals(25L, second.pointsSkipped)
        assertEquals(0L, second.cellsInserted)
        assertEquals(first, snapshot(target))
        assertEquals("merge keeps local settings", AppSettings(), targetSettings.state.value)
    }

    @Test
    fun `merge unites local and imported data`() = runTest {
        populate(source)
        val file = exportFrom(source, sourceSettings)
        Walker(target, clock).walk("local", 10.0, 10.0, points = 4)
        val localCells = target.cellDao().count()
        val summary = (repo(target, targetSettings).import(file.absolutePath, ImportMode.MERGE) as AppResult.Success).value
        assertEquals(3, summary.tracksInserted)
        assertEquals(4, target.trackDao().count())
        assertEquals(29L, target.pointDao().count())
        assertEquals(localCells + source.cellDao().count(), target.cellDao().count())
        assertEquals(target.cellDao().count(), target.statsDao().get()!!.cellCount)
    }

    @Test
    fun `same track id keeps the newer version`() = runTest {
        populate(source)
        val file = exportFrom(source, sourceSettings)
        val r = repo(target, targetSettings)
        r.import(file.absolutePath, ImportMode.MERGE)
        val local = target.trackDao().getById("a")!!
        target.trackDao().update(local.copy(status = TrackStatus.INTERRUPTED.name, updatedAt = local.updatedAt + 1))
        val summary = (r.import(file.absolutePath, ImportMode.MERGE) as AppResult.Success).value
        assertEquals(TrackStatus.INTERRUPTED.name, target.trackDao().getById("a")!!.status)
        assertEquals(3, summary.tracksSkipped)

        target.trackDao().update(local.copy(updatedAt = local.updatedAt - 10, status = TrackStatus.INTERRUPTED.name))
        r.import(file.absolutePath, ImportMode.MERGE)
        assertEquals("older local copy is replaced", TrackStatus.COMPLETED.name, target.trackDao().getById("a")!!.status)
    }

    @Test
    fun `replace deletes local data, keeps a safety backup and imports the archive`() = runTest {
        populate(source)
        val file = exportFrom(source, sourceSettings)
        Walker(target, clock).walk("local-only", 10.0, 10.0, points = 4)
        val summary = (repo(target, targetSettings).import(file.absolutePath, ImportMode.REPLACE) as AppResult.Success).value
        assertNull(target.trackDao().getById("local-only"))
        assertEquals(snapshot(source), snapshot(target))
        val safety = File(summary.safetyBackupPath!!)
        assertTrue(safety.exists())
        assertTrue("safety backup is itself valid", BackupReader().validate(safety).trackIds.contains("local-only"))
    }

    @Test
    fun `active track in a backup is imported as interrupted`() = runTest {
        Walker(source, clock).walk("live", 48.0, 11.0, points = 3, finish = false)
        val file = exportFrom(source, sourceSettings)
        repo(target, targetSettings).import(file.absolutePath, ImportMode.MERGE)
        val t = target.trackDao().getById("live")!!
        assertEquals(TrackStatus.INTERRUPTED.name, t.status)
        assertNotNull(t.endedAt)
        assertNull(target.trackDao().getActive())
    }

    @Test
    fun `corrupted archive is rejected without touching data`() = runTest {
        Walker(target, clock).walk("local", 10.0, 10.0, points = 2)
        val before = snapshot(target)
        val junk = tmp.newFile("junk.zip").apply { writeBytes(ByteArray(1000) { it.toByte() }) }
        val r = repo(target, targetSettings).import(junk.absolutePath, ImportMode.REPLACE)
        assertTrue((r as AppResult.Failure).error is AppError.CorruptedBackup)
        assertEquals(before, snapshot(target))
    }

    @Test
    fun `tampered entry fails the checksum`() = runTest {
        populate(source)
        val file = exportFrom(source, sourceSettings)
        val tampered = rewrite(file, BackupFormat.POINTS) { it.replaceFirst("\"latitude\":48.0", "\"latitude\":48.5") }
        val r = repo(target, targetSettings).inspect(tampered.absolutePath) as AppResult.Failure
        assertEquals(AppError.CorruptedBackup("checksum mismatch in points.jsonl"), r.error)
    }

    @Test
    fun `unsupported format version is reported`() = runTest {
        populate(source)
        val file = exportFrom(source, sourceSettings)
        val newer = rewrite(file, BackupFormat.MANIFEST) { it.replace("\"formatVersion\":1", "\"formatVersion\":99") }
        val r = repo(target, targetSettings).import(newer.absolutePath, ImportMode.MERGE) as AppResult.Failure
        assertEquals(AppError.UnsupportedBackupVersion(99, BackupFormat.FORMAT_VERSION), r.error)
        assertEquals(0, target.trackDao().count())
    }

    @Test
    fun `invalid records are rejected and nothing is imported`() = runTest {
        val manifest = BackupManifest(BackupFormat.FORMAT_ID, 1, 1, "x", 1, 1, 1, 0, emptyMap())
        val track = BackupTrack("t", 1000, 2000, "COMPLETED", 0.0, 1, 0, 2000, 1000, 2000)
        val badPoint = BackupPoint("p", "t", 123.0, 11.0, 1500, 5f, exploreRadiusMeters = 20)
        val file = buildArchive(manifest, listOf(track), listOf(badPoint), emptyList())
        val r = repo(target, targetSettings).import(file.absolutePath, ImportMode.MERGE) as AppResult.Failure
        assertEquals(AppError.CorruptedBackup("invalid coordinates"), r.error)
        assertEquals(0, target.trackDao().count())
    }

    @Test
    fun `point referencing a missing track is rejected`() = runTest {
        val manifest = BackupManifest(BackupFormat.FORMAT_ID, 1, 1, "x", 1, 0, 1, 0, emptyMap())
        val orphan = BackupPoint("p", "ghost", 48.0, 11.0, 1500, 5f, exploreRadiusMeters = 20)
        val file = buildArchive(manifest, emptyList(), listOf(orphan), emptyList())
        val r = repo(target, targetSettings).inspect(file.absolutePath) as AppResult.Failure
        assertEquals(AppError.CorruptedBackup("point references a missing track"), r.error)
    }

    @Test
    fun `unexpected entries and oversized records are rejected`() = runTest {
        populate(source)
        val file = exportFrom(source, sourceSettings)
        val extra = tmp.newFile("extra.zip")
        ZipOutputStream(extra.outputStream()).use { out ->
            ZipFile(file).use { zip -> zip.entries().asSequence().forEach { e -> out.putNextEntry(ZipEntry(e.name)); zip.getInputStream(e).copyTo(out); out.closeEntry() } }
            out.putNextEntry(ZipEntry("../evil.sh")); out.write("rm -rf".toByteArray()); out.closeEntry()
        }
        assertEquals(AppError.CorruptedBackup("unexpected entry '../evil.sh'"), (repo(target, targetSettings).inspect(extra.absolutePath) as AppResult.Failure).error)

        val longLine = rewrite(file, BackupFormat.CELLS) { "x".repeat(BackupFormat.MAX_LINE_BYTES + 10) + "\n" }
        assertEquals(AppError.CorruptedBackup("record too long in cells.jsonl"), (repo(target, targetSettings).inspect(longLine.absolutePath) as AppResult.Failure).error)
    }

    /** Rewrites one entry verbatim; the manifest (checksums, sizes) is left as it was. */
    private fun rewrite(file: File, name: String, transform: (String) -> String): File {
        val out = tmp.newFile("rewritten-${System.nanoTime()}.zip")
        ZipOutputStream(out.outputStream()).use { zos ->
            ZipFile(file).use { zip ->
                zip.entries().asSequence().forEach { e ->
                    val text = zip.getInputStream(e).readBytes().toString(Charsets.UTF_8)
                    zos.putNextEntry(ZipEntry(e.name))
                    zos.write((if (e.name == name) transform(text) else text).toByteArray())
                    zos.closeEntry()
                }
            }
        }
        return out
    }

    /** Builds an archive with correct checksums from records (to test record-level validation). */
    private fun buildArchive(manifest: BackupManifest, tracks: List<BackupTrack>, points: List<BackupPoint>, cells: List<BackupCell>): File {
        val json = BackupFormat.json
        val entries = linkedMapOf(
            BackupFormat.SETTINGS to json.encodeToString(BackupSettings.serializer(), AppSettings().toBackup()),
            BackupFormat.TRACKS to tracks.joinToString("") { json.encodeToString(BackupTrack.serializer(), it) + "\n" },
            BackupFormat.POINTS to points.joinToString("") { json.encodeToString(BackupPoint.serializer(), it) + "\n" },
            BackupFormat.CELLS to cells.joinToString("") { json.encodeToString(BackupCell.serializer(), it) + "\n" },
        )
        val infos = entries.mapValues { (_, text) ->
            val bytes = text.toByteArray()
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            BackupEntryInfo(sha, bytes.size.toLong(), text.count { it == '\n' }.toLong())
        }
        val file = tmp.newFile("built-${System.nanoTime()}.zip")
        ZipOutputStream(file.outputStream()).use { zos ->
            entries.forEach { (name, text) -> zos.putNextEntry(ZipEntry(name)); zos.write(text.toByteArray()); zos.closeEntry() }
            zos.putNextEntry(ZipEntry(BackupFormat.MANIFEST))
            zos.write(json.encodeToString(BackupManifest.serializer(), manifest.copy(entries = infos)).toByteArray())
            zos.closeEntry()
        }
        return file
    }
}
