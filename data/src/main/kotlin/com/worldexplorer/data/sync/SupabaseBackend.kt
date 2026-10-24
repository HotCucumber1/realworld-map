package com.worldexplorer.data.sync

import android.util.Log
import com.worldexplorer.domain.result.AppError
import com.worldexplorer.domain.tracking.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.UnknownHostException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Retry policy for idempotent requests: exponential backoff on network errors, 5xx and 429. */
data class RetryPolicy(val maxAttempts: Int = 3, val initialDelayMillis: Long = 1_000, val factor: Double = 2.0)

/**
 * Supabase client over plain HTTPS: GoTrue (e-mail/password auth) and PostgREST (tables protected
 * by Row Level Security, see supabase/schema.sql). No Supabase SDK is needed.
 */
class SupabaseBackend(
    private val config: SupabaseConfig,
    private val http: OkHttpClient,
    private val sessions: SessionStore,
    private val clock: Clock,
    private val retry: RetryPolicy = RetryPolicy(),
) : CloudBackend {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = true; encodeDefaults = true }
    private val refreshMutex = Mutex()
    private val base: HttpUrl by lazy { config.url.trimEnd('/').toHttpUrl() }

    // ---- auth ---------------------------------------------------------------------------------

    override suspend fun signIn(email: String, password: String): Session {
        val body = json.encodeToString(Credentials.serializer(), Credentials(email, password))
        val response = execute(authRequest("token", mapOf("grant_type" to "password"), body), retryable = false)
        return response.use {
            if (!it.isSuccessful) throw authError(it)
            parseSession(it.body.string())
        }
    }

    override suspend fun signUp(email: String, password: String): SignUpResult {
        val body = json.encodeToString(Credentials.serializer(), Credentials(email, password))
        val response = execute(authRequest("signup", emptyMap(), body), retryable = false)
        return response.use {
            if (!it.isSuccessful) throw authError(it)
            val text = it.body.string()
            val obj = json.parseToJsonElement(text).jsonObject
            if (obj.containsKey("access_token")) SignUpResult.SignedIn(parseSession(text)) else SignUpResult.ConfirmationRequired
        }
    }

    override suspend fun refresh(session: Session): Session {
        val body = json.encodeToString(RefreshBody.serializer(), RefreshBody(session.refreshToken))
        val response = execute(authRequest("token", mapOf("grant_type" to "refresh_token"), body), retryable = true)
        return response.use {
            if (it.code in 400..499) throw SyncException(AppError.SessionExpired)
            if (!it.isSuccessful) throw SyncException(AppError.BackendUnavailable("HTTP ${it.code}"))
            parseSession(it.body.string())
        }
    }

    override suspend fun signOut(session: Session) {
        val request = Request.Builder()
            .url(base.newBuilder().addPathSegments("auth/v1/logout").build())
            .header("apikey", config.anonKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        runCatching { execute(request, retryable = false).close() }
            .onFailure { Log.w(TAG, "remote sign-out failed (local session is cleared anyway)") }
    }

    // ---- data ---------------------------------------------------------------------------------

    override suspend fun pushTracks(tracks: List<RemoteTrack>) =
        upsert("tracks", "user_id,id", merge = true, json.encodeToString(ListSerializer(RemoteTrack.serializer()), tracks))

    override suspend fun pushPoints(points: List<RemotePoint>) =
        upsert("location_points", "user_id,id", merge = false, json.encodeToString(ListSerializer(RemotePoint.serializer()), points))

    override suspend fun pushCells(cells: List<RemoteCell>) =
        // merge: the server trigger keeps the earliest first_visited_at (see supabase/schema.sql).
        upsert("explored_cells", "user_id,cell_row,cell_col", merge = true, json.encodeToString(ListSerializer(RemoteCell.serializer()), cells))

    override suspend fun pullTracks(userId: String, afterSeq: Long, limit: Int) = pull("tracks", userId, afterSeq, limit, RemoteTrack.serializer())
    override suspend fun pullPoints(userId: String, afterSeq: Long, limit: Int) = pull("location_points", userId, afterSeq, limit, RemotePoint.serializer())
    override suspend fun pullCells(userId: String, afterSeq: Long, limit: Int) = pull("explored_cells", userId, afterSeq, limit, RemoteCell.serializer())

    override suspend fun deleteAllData(userId: String) {
        for (table in listOf("location_points", "explored_cells", "tracks")) {
            authorized { token ->
                Request.Builder()
                    .url(rest(table).addQueryParameter("user_id", "eq.$userId").build())
                    .restHeaders(token)
                    .delete()
                    .build()
            }.close()
        }
    }

    override suspend fun deleteAccount() {
        authorized { token ->
            Request.Builder()
                .url(base.newBuilder().addPathSegments("rest/v1/rpc/delete_user").build())
                .restHeaders(token)
                .post("{}".toRequestBody(JSON))
                .build()
        }.close()
    }

    private suspend fun upsert(table: String, conflictColumns: String, merge: Boolean, body: String) {
        authorized { token ->
            Request.Builder()
                .url(rest(table).addQueryParameter("on_conflict", conflictColumns).build())
                .restHeaders(token)
                .header("Prefer", "resolution=${if (merge) "merge" else "ignore"}-duplicates,return=minimal")
                .post(body.toRequestBody(JSON))
                .build()
        }.close()
    }

    private suspend fun <T> pull(table: String, userId: String, afterSeq: Long, limit: Int, serializer: KSerializer<T>): List<T> {
        val response = authorized { token ->
            Request.Builder()
                .url(
                    rest(table)
                        .addQueryParameter("select", "*")
                        .addQueryParameter("user_id", "eq.$userId")
                        .addQueryParameter("seq", "gt.$afterSeq")
                        .addQueryParameter("order", "seq.asc")
                        .addQueryParameter("limit", limit.toString())
                        .build(),
                )
                .restHeaders(token)
                .get()
                .build()
        }
        return response.use {
            try {
                json.decodeFromString(ListSerializer(serializer), it.body.string())
            } catch (e: IllegalArgumentException) {
                throw SyncException(AppError.BackendUnavailable("unexpected response"), e)
            }
        }
    }

    /**
     * Runs a request with the current access token. Refreshes the token shortly before expiry and
     * once more on HTTP 401; if refreshing fails the session is dropped (SessionExpired).
     */
    private suspend fun authorized(build: (String) -> Request): Response {
        var session = validSession(forceRefresh = false)
        var response = execute(build(session.accessToken), retryable = true)
        if (response.code == 401) {
            response.close()
            session = validSession(forceRefresh = true)
            response = execute(build(session.accessToken), retryable = true)
        }
        if (response.isSuccessful) return response
        response.use { throw httpError(it) }
    }

    private suspend fun validSession(forceRefresh: Boolean): Session = refreshMutex.withLock {
        val current = sessions.session.value ?: throw SyncException(AppError.NotSignedIn)
        val expiresSoon = current.expiresAtUtc - clock.nowUtcMillis() < REFRESH_MARGIN_MILLIS
        if (!forceRefresh && !expiresSoon) return current
        try {
            refresh(current).also(sessions::save)
        } catch (e: SyncException) {
            if (e.error == AppError.SessionExpired) sessions.save(null)
            throw e
        }
    }

    private suspend fun execute(request: Request, retryable: Boolean): Response {
        var attempt = 0
        var wait = retry.initialDelayMillis
        while (true) {
            attempt++
            val result = runCatching { http.newCall(request).await() }
            val response = result.getOrNull()
            val error = result.exceptionOrNull()
            if (error is CancellationException) throw error
            val transient = error is IOException || (response != null && (response.code >= 500 || response.code == 429))
            if (!transient || !retryable || attempt >= retry.maxAttempts) {
                if (error != null) {
                    throw SyncException(if (error is UnknownHostException) AppError.NoNetwork else AppError.BackendUnavailable(error.javaClass.simpleName), error)
                }
                return response!!
            }
            response?.close()
            delay(wait)
            wait = (wait * retry.factor).toLong()
        }
    }

    private fun httpError(response: Response): SyncException {
        val message = runCatching { response.body.string().take(300) }.getOrDefault("")
        Log.w(TAG, "backend HTTP ${response.code}")
        return when (response.code) {
            401 -> SyncException(AppError.SessionExpired)
            in 500..599, 429 -> SyncException(AppError.BackendUnavailable("HTTP ${response.code}"))
            else -> SyncException(AppError.BackendRejected(response.code, message))
        }
    }

    private fun authError(response: Response): SyncException {
        val text = runCatching { response.body.string() }.getOrDefault("")
        val reason = runCatching {
            val obj = json.parseToJsonElement(text).jsonObject
            (obj["error_description"] ?: obj["msg"] ?: obj["message"] ?: obj["error"])?.jsonPrimitive?.content
        }.getOrNull() ?: "HTTP ${response.code}"
        return when (response.code) {
            in 400..499 -> SyncException(AppError.InvalidCredentials(reason))
            else -> SyncException(AppError.BackendUnavailable(reason))
        }
    }

    private fun parseSession(text: String): Session {
        val dto = try {
            json.decodeFromString(SessionDto.serializer(), text)
        } catch (e: IllegalArgumentException) {
            throw SyncException(AppError.BackendUnavailable("unexpected auth response"), e)
        }
        return Session(dto.accessToken, dto.refreshToken, clock.nowUtcMillis() + dto.expiresIn * 1000, dto.user.id, dto.user.email ?: "")
    }

    private fun authRequest(path: String, query: Map<String, String>, body: String): Request {
        val url = base.newBuilder().addPathSegments("auth/v1/$path").apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return Request.Builder().url(url).header("apikey", config.anonKey).post(body.toRequestBody(JSON)).build()
    }

    private fun rest(table: String): HttpUrl.Builder = base.newBuilder().addPathSegments("rest/v1/$table")

    private fun Request.Builder.restHeaders(token: String) = apply {
        header("apikey", config.anonKey)
        header("Authorization", "Bearer $token")
        header("Content-Type", "application/json")
    }

    @Serializable private data class Credentials(val email: String, val password: String)
    @Serializable private data class RefreshBody(@SerialName("refresh_token") val refreshToken: String)
    @Serializable private data class SessionUser(val id: String, val email: String? = null)
    @Serializable private data class SessionDto(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String,
        @SerialName("expires_in") val expiresIn: Long,
        val user: SessionUser,
    )

    private companion object {
        const val TAG = "WorldExplorer"
        const val REFRESH_MARGIN_MILLIS = 60_000L
        val JSON = "application/json".toMediaType()
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, r, _ -> r.close() }
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
