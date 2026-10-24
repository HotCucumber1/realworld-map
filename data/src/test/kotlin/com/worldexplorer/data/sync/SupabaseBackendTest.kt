package com.worldexplorer.data.sync

import com.worldexplorer.data.MutableClock
import com.worldexplorer.domain.result.AppError
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class SupabaseBackendTest {
    private val server = MockWebServer()
    private val clock = MutableClock()
    private val sessions = InMemorySessionStore()
    private lateinit var backend: SupabaseBackend

    private val sessionJson = """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600,"token_type":"bearer","user":{"id":"u-1","email":"a@example.com"}}"""

    @Before
    fun setUp() {
        server.start()
        val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
        backend = SupabaseBackend(SupabaseConfig(server.url("/").toString(), "anon-key"), http, sessions, clock, RetryPolicy(maxAttempts = 3, initialDelayMillis = 1))
    }

    @After fun tearDown() = server.shutdown()

    private fun signedIn(expiresIn: Long = 3_600_000) =
        sessions.save(Session("old-access", "old-refresh", clock.now + expiresIn, "u-1", "a@example.com"))

    @Test
    fun `sign in sends credentials with the anon key and parses the session`() = runTest {
        server.enqueue(MockResponse().setBody(sessionJson))
        val s = backend.signIn("a@example.com", "secret1")
        val req = server.takeRequest()
        assertEquals("/auth/v1/token?grant_type=password", req.path)
        assertEquals("anon-key", req.getHeader("apikey"))
        assertTrue(req.body.readUtf8().contains("\"password\":\"secret1\""))
        assertEquals("u-1", s.userId)
        assertEquals(clock.now + 3_600_000, s.expiresAtUtc)
    }

    @Test
    fun `wrong password becomes InvalidCredentials with the server message`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant","error_description":"Invalid login credentials"}"""))
        try {
            backend.signIn("a@example.com", "wrong1"); fail()
        } catch (e: SyncException) {
            assertEquals(AppError.InvalidCredentials("Invalid login credentials"), e.error)
        }
    }

    @Test
    fun `sign up without session means e-mail confirmation is required`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"u-2","email":"b@example.com","confirmation_sent_at":"2026-01-01"}"""))
        assertEquals(SignUpResult.ConfirmationRequired, backend.signUp("b@example.com", "secret1"))
    }

    @Test
    fun `upsert uses on_conflict, merge preference and the bearer token`() = runTest {
        signedIn()
        server.enqueue(MockResponse().setResponseCode(201))
        backend.pushTracks(listOf(RemoteTrack("u-1", "t", 1, 2, "COMPLETED", 10.0, 3, 0, 2, 1, 2)))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/rest/v1/tracks?on_conflict=user_id%2Cid", req.path)
        assertEquals("Bearer old-access", req.getHeader("Authorization"))
        assertEquals("resolution=merge-duplicates,return=minimal", req.getHeader("Prefer"))
        val body = req.body.readUtf8()
        assertTrue("seq is server-assigned and never sent: $body", !body.contains("seq"))
        assertTrue(body.contains("\"user_id\":\"u-1\""))
    }

    @Test
    fun `points and cells are inserted with ignore-duplicates`() = runTest {
        signedIn()
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(201))
        backend.pushPoints(listOf(RemotePoint("u-1", "p", "t", 48.0, 11.0, 5, 3f, null, null, null, "GPS", 20)))
        backend.pushCells(listOf(RemoteCell("u-1", 1, 2, 3)))
        assertEquals("resolution=ignore-duplicates,return=minimal", server.takeRequest().getHeader("Prefer"))
        val cells = server.takeRequest()
        assertEquals("/rest/v1/explored_cells?on_conflict=user_id%2Ccell_row%2Ccell_col", cells.path)
    }

    @Test
    fun `pull asks only for rows after the cursor in sequence order`() = runTest {
        signedIn()
        server.enqueue(MockResponse().setBody("""[{"user_id":"u-1","cell_row":5,"cell_col":6,"first_visited_at":7,"seq":42}]"""))
        val cells = backend.pullCells("u-1", afterSeq = 41, limit = 100)
        assertEquals(listOf(RemoteCell("u-1", 5, 6, 7, seq = 42)), cells)
        val path = server.takeRequest().path!!
        assertTrue(path, path.contains("user_id=eq.u-1") && path.contains("seq=gt.41") && path.contains("order=seq.asc") && path.contains("limit=100"))
    }

    @Test
    fun `expired token is refreshed once and the request retried`() = runTest {
        signedIn()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"JWT expired"}"""))
        server.enqueue(MockResponse().setBody(sessionJson))
        server.enqueue(MockResponse().setBody("[]"))
        backend.pullTracks("u-1", 0, 10)
        assertEquals("Bearer old-access", server.takeRequest().getHeader("Authorization"))
        val refresh = server.takeRequest()
        assertEquals("/auth/v1/token?grant_type=refresh_token", refresh.path)
        assertTrue(refresh.body.readUtf8().contains("old-refresh"))
        assertEquals("Bearer new-access", server.takeRequest().getHeader("Authorization"))
        assertEquals("new-access", sessions.session.value!!.accessToken)
    }

    @Test
    fun `token about to expire is refreshed before the request`() = runTest {
        signedIn(expiresIn = 10_000)
        server.enqueue(MockResponse().setBody(sessionJson))
        server.enqueue(MockResponse().setBody("[]"))
        backend.pullPoints("u-1", 0, 10)
        assertEquals("/auth/v1/token?grant_type=refresh_token", server.takeRequest().path)
        assertEquals("Bearer new-access", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `failed refresh ends the session`() = runTest {
        signedIn()
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        try {
            backend.pullTracks("u-1", 0, 10); fail()
        } catch (e: SyncException) {
            assertEquals(AppError.SessionExpired, e.error)
        }
        assertNull(sessions.session.value)
    }

    @Test
    fun `server errors are retried with backoff, then reported`() = runTest {
        signedIn()
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(502))
        server.enqueue(MockResponse().setBody("[]"))
        assertEquals(emptyList<RemoteTrack>(), backend.pullTracks("u-1", 0, 10))
        assertEquals(3, server.requestCount)

        repeat(3) { server.enqueue(MockResponse().setResponseCode(500)) }
        try {
            backend.pullTracks("u-1", 0, 10); fail()
        } catch (e: SyncException) {
            assertEquals(AppError.BackendUnavailable("HTTP 500"), e.error)
        }
    }

    @Test
    fun `permission errors are not retried`() = runTest {
        signedIn()
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"new row violates row-level security policy"}"""))
        try {
            backend.pushCells(listOf(RemoteCell("someone-else", 1, 1, 1))); fail()
        } catch (e: SyncException) {
            assertTrue(e.error is AppError.BackendRejected && (e.error as AppError.BackendRejected).httpCode == 403)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `requests without a session fail with NotSignedIn and never hit the network`() = runTest {
        try {
            backend.pullCells("u-1", 0, 10); fail()
        } catch (e: SyncException) {
            assertEquals(AppError.NotSignedIn, e.error)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `delete everything removes rows of the user and the account via rpc`() = runTest {
        signedIn()
        repeat(4) { server.enqueue(MockResponse().setResponseCode(204)) }
        backend.deleteAllData("u-1")
        val paths = (0 until 3).map { server.takeRequest() }.map { it.method + " " + it.path }
        assertEquals(listOf("DELETE /rest/v1/location_points?user_id=eq.u-1", "DELETE /rest/v1/explored_cells?user_id=eq.u-1", "DELETE /rest/v1/tracks?user_id=eq.u-1"), paths)
        backend.deleteAccount()
        assertEquals("/rest/v1/rpc/delete_user", server.takeRequest().path)
    }
}
