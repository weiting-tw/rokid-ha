package tw.rokidha.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tw.rokidha.homeassistant.config.AccessToken
import tw.rokidha.homeassistant.config.HaConfig
import tw.rokidha.homeassistant.config.HaServerUrl
import tw.rokidha.homeassistant.credentials.CredentialStore

/** Runs on a device/emulator against the real Android Keystore (not part of CI). */
@RunWith(AndroidJUnit4::class)
class KeystoreCredentialStoreTest {
    @Test fun tokenIsEncryptedRestoredAndDeleted() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = SharedPrefsKeyValueStore(ctx, "keystore_test")
        val store = CredentialStore(KeystoreSecretCipher("keystore_test_key"), prefs)
        val token = "test.token.SECRET"
        val cfg = HaConfig(HaServerUrl.of("http://homeassistant.local:8123"), AccessToken.parse(token)!!)
        store.save(cfg)
        assertFalse(prefs.getString(CredentialStore.KEY_TOKEN)!!.contains("SECRET"))
        assertEquals(cfg, (CredentialStore(KeystoreSecretCipher("keystore_test_key"), prefs).load() as CredentialStore.LoadResult.Ok).config)
        store.clear()
        assertTrue(store.load() is CredentialStore.LoadResult.None)
    }
}
