package tw.rokidha.homeassistant.credentials

import tw.rokidha.homeassistant.config.AccessToken
import tw.rokidha.homeassistant.config.HaConfig
import tw.rokidha.homeassistant.config.HaServerUrl
import java.util.Base64

/** Authenticated encryption backed by a non-exportable key (Android Keystore on device). */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
    /** Deletes the key; previously encrypted data becomes unreadable. */
    fun destroyKey()
}

/** Minimal persistent string map (SharedPreferences on device). */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(vararg keys: String)
}

/**
 * Stores the HA URL (plain) and access token (encrypted with [SecretCipher]).
 * The token never touches the store in plain text and is never logged.
 */
class CredentialStore(
    private val cipher: SecretCipher,
    private val store: KeyValueStore,
) {
    sealed interface LoadResult {
        data object None : LoadResult
        data class Ok(val config: HaConfig) : LoadResult
        /** Stored data cannot be decrypted (key invalidated, data corrupted). It has been cleared. */
        data class Unreadable(val reason: String) : LoadResult
    }

    fun save(config: HaConfig) {
        val blob = cipher.encrypt(config.token.value.toByteArray(Charsets.UTF_8))
        store.putString(KEY_TOKEN, Base64.getEncoder().encodeToString(blob))
        store.putString(KEY_URL, config.url.toString())
    }

    fun load(): LoadResult {
        val url = store.getString(KEY_URL)
        val enc = store.getString(KEY_TOKEN)
        if (url == null || enc == null) return LoadResult.None
        return try {
            val plain = cipher.decrypt(Base64.getDecoder().decode(enc)).toString(Charsets.UTF_8)
            val token = AccessToken.parse(plain) ?: error("stored token is empty")
            LoadResult.Ok(HaConfig(HaServerUrl.of(url), token))
        } catch (e: Exception) {
            clear()
            LoadResult.Unreadable("Stored credentials could not be read (${e.javaClass.simpleName}); please enter them again.")
        }
    }

    fun hasCredentials(): Boolean = store.getString(KEY_TOKEN) != null

    /** Deletes URL, encrypted token and the encryption key itself. */
    fun clear() {
        store.remove(KEY_URL, KEY_TOKEN)
        runCatching { cipher.destroyKey() }
    }

    companion object {
        const val KEY_URL = "ha.url"
        const val KEY_TOKEN = "ha.token.enc"
    }
}
