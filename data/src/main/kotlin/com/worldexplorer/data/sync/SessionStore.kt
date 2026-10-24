package com.worldexplorer.data.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

interface SessionStore {
    val session: StateFlow<Session?>
    fun save(session: Session?)
}

/**
 * Keeps auth tokens encrypted with an AES-256-GCM key that never leaves the Android Keystore.
 * The ciphertext lives in private SharedPreferences (excluded from cloud/device backups).
 */
@Singleton
class KeystoreSessionStore @Inject constructor(@ApplicationContext context: Context) : SessionStore {
    private val prefs = context.getSharedPreferences("secure_session", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    override val session: StateFlow<Session?> = state.asStateFlow()

    override fun save(session: Session?) {
        state.value = session
        if (session == null) {
            prefs.edit().remove(KEY).apply()
            return
        }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val plain = Json.encodeToString(StoredSession.serializer(), StoredSession.from(session)).toByteArray()
            val payload = cipher.iv + cipher.doFinal(plain)
            prefs.edit().putString(KEY, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
        } catch (e: Exception) {
            // The session stays in memory for this process; the user signs in again after a restart.
            Log.e("WorldExplorer", "session encryption failed", e)
        }
    }

    private fun load(): Session? {
        val stored = prefs.getString(KEY, null) ?: return null
        return try {
            val payload = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, payload, 0, IV_SIZE))
            val plain = cipher.doFinal(payload, IV_SIZE, payload.size - IV_SIZE)
            Json.decodeFromString(StoredSession.serializer(), String(plain)).toSession()
        } catch (e: Exception) {
            Log.w("WorldExplorer", "stored session unreadable, signing out", e)
            prefs.edit().remove(KEY).apply()
            null
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    @Serializable
    private data class StoredSession(val a: String, val r: String, val e: Long, val u: String, val m: String) {
        fun toSession() = Session(a, r, e, u, m)
        companion object {
            fun from(s: Session) = StoredSession(s.accessToken, s.refreshToken, s.expiresAtUtc, s.userId, s.email)
        }
    }

    private companion object {
        const val KEY = "session"
        const val ALIAS = "world_explorer_session_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}

/** In-memory store used in tests and when the Keystore is unavailable. */
class InMemorySessionStore(initial: Session? = null) : SessionStore {
    private val state = MutableStateFlow(initial)
    override val session: StateFlow<Session?> = state.asStateFlow()
    override fun save(session: Session?) { state.value = session }
}
