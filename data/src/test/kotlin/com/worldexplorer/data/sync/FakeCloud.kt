package com.worldexplorer.data.sync

import com.worldexplorer.domain.result.AppError

/**
 * In-memory backend that mimics the Supabase schema: per-user rows, server-assigned increasing
 * sequence numbers, upsert semantics (tracks: newer updated_at wins; points/cells: ignore duplicates).
 */
class FakeCloud {
    private var seq = 0L
    val tracks = LinkedHashMap<Pair<String, String>, RemoteTrack>()
    val points = LinkedHashMap<Pair<String, String>, RemotePoint>()
    val cells = LinkedHashMap<Triple<String, Int, Int>, RemoteCell>()
    var requests = 0
    /** Fails the given number of upcoming requests with a network error. */
    var failNext = 0
    var deletedAccounts = mutableListOf<String>()

    fun backendFor(userId: String): SyncBackend = object : SyncBackend {
        private fun hit() {
            requests++
            if (failNext > 0) { failNext--; throw SyncException(AppError.BackendUnavailable("simulated")) }
        }

        override suspend fun pushTracks(tracks: List<RemoteTrack>) {
            hit()
            tracks.forEach { t ->
                require(t.userId == userId) { "RLS violation" }
                val key = t.userId to t.id
                val existing = this@FakeCloud.tracks[key]
                if (existing == null || t.updatedAt > existing.updatedAt) this@FakeCloud.tracks[key] = t.copy(seq = ++seq)
            }
        }

        override suspend fun pushPoints(points: List<RemotePoint>) {
            hit()
            points.forEach { p ->
                require(p.userId == userId) { "RLS violation" }
                require(this@FakeCloud.tracks.containsKey(p.userId to p.trackId)) { "FK violation" }
                this@FakeCloud.points.putIfAbsent(p.userId to p.id, p.copy(seq = ++seq))
            }
        }

        override suspend fun pushCells(cells: List<RemoteCell>) {
            hit()
            cells.forEach { c ->
                val key = Triple(c.userId, c.row, c.column)
                val existing = this@FakeCloud.cells[key]
                if (existing == null || c.firstVisitedAt < existing.firstVisitedAt) this@FakeCloud.cells[key] = c.copy(seq = ++seq)
            }
        }

        override suspend fun pullTracks(userId: String, afterSeq: Long, limit: Int) =
            also { hit() }.let { tracks.values.filter { it.userId == userId && it.seq > afterSeq }.sortedBy { it.seq }.take(limit) }

        override suspend fun pullPoints(userId: String, afterSeq: Long, limit: Int) =
            also { hit() }.let { points.values.filter { it.userId == userId && it.seq > afterSeq }.sortedBy { it.seq }.take(limit) }

        override suspend fun pullCells(userId: String, afterSeq: Long, limit: Int) =
            also { hit() }.let { cells.values.filter { it.userId == userId && it.seq > afterSeq }.sortedBy { it.seq }.take(limit) }

        override suspend fun deleteAllData(userId: String) {
            hit()
            tracks.keys.removeAll { it.first == userId }
            points.keys.removeAll { it.first == userId }
            cells.keys.removeAll { it.first == userId }
        }

        override suspend fun deleteAccount() {
            deleteAllData(userId)
            deletedAccounts += userId
        }
    }
}
