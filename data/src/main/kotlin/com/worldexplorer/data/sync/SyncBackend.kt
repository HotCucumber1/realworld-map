package com.worldexplorer.data.sync

import com.worldexplorer.domain.result.AppError
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Expected backend failure carrying a typed error for the UI. */
class SyncException(val error: AppError, cause: Throwable? = null) : Exception(error.toString(), cause)

data class Session(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtUtc: Long,
    val userId: String,
    val email: String,
)

sealed interface SignUpResult {
    data class SignedIn(val session: Session) : SignUpResult
    data object ConfirmationRequired : SignUpResult
}

/** Authentication against the backend. */
interface AuthBackend {
    suspend fun signIn(email: String, password: String): Session
    suspend fun signUp(email: String, password: String): SignUpResult
    suspend fun refresh(session: Session): Session
    suspend fun signOut(session: Session)
}

/**
 * Data operations. Every write is idempotent (upsert by primary key), so a retried or repeated
 * request never duplicates rows. Pulls are incremental by a server-assigned monotonically
 * increasing sequence number ([RemoteTrack.seq] etc.).
 */
interface SyncBackend {
    suspend fun pushTracks(tracks: List<RemoteTrack>)
    suspend fun pushPoints(points: List<RemotePoint>)
    suspend fun pushCells(cells: List<RemoteCell>)
    suspend fun pullTracks(userId: String, afterSeq: Long, limit: Int): List<RemoteTrack>
    suspend fun pullPoints(userId: String, afterSeq: Long, limit: Int): List<RemotePoint>
    suspend fun pullCells(userId: String, afterSeq: Long, limit: Int): List<RemoteCell>
    suspend fun deleteAllData(userId: String)
    suspend fun deleteAccount()
}

/** A backend that offers both authentication and data sync. */
interface CloudBackend : AuthBackend, SyncBackend

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RemoteTrack(
    @SerialName("user_id") val userId: String,
    val id: String,
    @SerialName("started_at") val startedAt: Long,
    @SerialName("ended_at") val endedAt: Long?,
    val status: String,
    @SerialName("distance_meters") val distanceMeters: Double,
    @SerialName("point_count") val pointCount: Int,
    @SerialName("rejected_point_count") val rejectedPointCount: Int,
    @SerialName("last_point_at") val lastPointAt: Long?,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    /** Assigned by the server; never sent. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val seq: Long = 0,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RemotePoint(
    @SerialName("user_id") val userId: String,
    val id: String,
    @SerialName("track_id") val trackId: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("timestamp_utc") val timestampUtc: Long,
    @SerialName("accuracy_meters") val accuracyMeters: Float,
    @SerialName("altitude_meters") val altitudeMeters: Double?,
    @SerialName("speed_mps") val speedMetersPerSecond: Float?,
    @SerialName("bearing_degrees") val bearingDegrees: Float?,
    val source: String,
    @SerialName("explore_radius_meters") val exploreRadiusMeters: Int,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val seq: Long = 0,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RemoteCell(
    @SerialName("user_id") val userId: String,
    @SerialName("cell_row") val row: Int,
    @SerialName("cell_col") val column: Int,
    @SerialName("first_visited_at") val firstVisitedAt: Long,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val seq: Long = 0,
)

/** Backend endpoint configuration (from local.properties via BuildConfig; empty = not configured). */
data class SupabaseConfig(val url: String, val anonKey: String) {
    val isConfigured: Boolean get() = url.startsWith("https://") && anonKey.isNotBlank()
}
