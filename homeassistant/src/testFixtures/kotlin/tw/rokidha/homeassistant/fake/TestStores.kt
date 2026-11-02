package tw.rokidha.homeassistant.fake

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import tw.rokidha.homeassistant.credentials.KeyValueStore
import tw.rokidha.homeassistant.credentials.SecretCipher
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class InMemoryKeyValueStore : KeyValueStore {
    val map = ConcurrentHashMap<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
    override fun remove(vararg keys: String) { keys.forEach { map.remove(it) } }
}

/** AES-256-GCM with an in-memory key: same format as the Android Keystore implementation (IV ‖ ciphertext). */
class SoftwareAesGcmCipher : SecretCipher {
    @Volatile private var key: SecretKey? = null
    var destroyed = false
        private set

    private fun key(): SecretKey = key ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it; destroyed = false }

    override fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        return iv + c.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        val k = key ?: error("no key")
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, blob, 0, 12)) }
        return c.doFinal(blob, 12, blob.size - 12)
    }

    override fun destroyKey() {
        key = null
        destroyed = true
    }
}

/** Polls [condition] until true or fails after [timeoutMs]. */
suspend fun eventually(timeoutMs: Long = 5_000, message: String = "condition not met", condition: () -> Boolean) {
    try {
        withTimeout(timeoutMs) { while (!condition()) delay(20) }
    } catch (e: Exception) {
        throw AssertionError("$message within ${timeoutMs}ms", e)
    }
}
