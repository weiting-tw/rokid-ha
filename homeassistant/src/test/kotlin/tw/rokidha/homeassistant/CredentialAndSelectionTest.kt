package tw.rokidha.homeassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tw.rokidha.homeassistant.config.AccessToken
import tw.rokidha.homeassistant.config.HaConfig
import tw.rokidha.homeassistant.config.HaServerUrl
import tw.rokidha.homeassistant.credentials.CredentialStore
import tw.rokidha.homeassistant.fake.FakeHomeAssistant
import tw.rokidha.homeassistant.fake.InMemoryKeyValueStore
import tw.rokidha.homeassistant.fake.SoftwareAesGcmCipher
import tw.rokidha.homeassistant.notifications.HaNotification
import tw.rokidha.homeassistant.model.HaEntity
import tw.rokidha.homeassistant.selection.GlassesSelection
import tw.rokidha.homeassistant.selection.SelectionStore
import tw.rokidha.homeassistant.selection.ServiceCallGuard
import tw.rokidha.homeassistant.selection.ServiceCatalog

/** Secure token persistence, disconnect/credential deletion, selection and allowlist. */
class CredentialAndSelectionTest {
    private val token = FakeHomeAssistant.VALID_TOKEN
    private val cfg = HaConfig(HaServerUrl.of("http://homeassistant.local:8123"), AccessToken.parse(token)!!)

    @Test fun `token is persisted encrypted and restored after restart`() {
        val kv = InMemoryKeyValueStore()
        val cipher = SoftwareAesGcmCipher()
        CredentialStore(cipher, kv).save(cfg)
        assertTrue(kv.map.values.none { it.contains(token) || it.contains("SECRET_SIGNATURE") })
        // "App restart": new store instance over the same persisted data and key.
        val loaded = CredentialStore(cipher, kv).load() as CredentialStore.LoadResult.Ok
        assertEquals(cfg, loaded.config)
    }

    @Test fun `ciphertext differs per save (random IV)`() {
        val kv = InMemoryKeyValueStore()
        val store = CredentialStore(SoftwareAesGcmCipher(), kv)
        store.save(cfg); val a = kv.map[CredentialStore.KEY_TOKEN]
        store.save(cfg); val b = kv.map[CredentialStore.KEY_TOKEN]
        assertTrue(a != b)
    }

    @Test fun `disconnect clears credentials and destroys the key`() {
        val kv = InMemoryKeyValueStore()
        val cipher = SoftwareAesGcmCipher()
        val store = CredentialStore(cipher, kv)
        store.save(cfg)
        store.clear()
        assertTrue(kv.map.isEmpty())
        assertTrue(cipher.destroyed)
        assertFalse(store.hasCredentials())
        assertEquals(CredentialStore.LoadResult.None, store.load())
    }

    @Test fun `unreadable credentials are wiped and reported`() {
        val kv = InMemoryKeyValueStore()
        val cipher = SoftwareAesGcmCipher()
        CredentialStore(cipher, kv).save(cfg)
        cipher.destroyKey() // e.g. Keystore key invalidated
        val r = CredentialStore(cipher, kv).load()
        assertTrue(r is CredentialStore.LoadResult.Unreadable)
        assertTrue(kv.map.isEmpty())
    }

    @Test fun `nothing is exposed by default and favorites are capped`() {
        var s = GlassesSelection()
        assertTrue(s.favorites.isEmpty() && s.quickActions.isEmpty())
        (1..10).forEach { s = s.withFavorite("light.l$it", true) }
        assertEquals(GlassesSelection.MAX_FAVORITES, s.favorites.size)
    }

    @Test fun `guard only allows configured quick actions from the catalog`() {
        var s = GlassesSelection().withFavorite("light.living_room", true).withFavorite("lock.front_door", true)
        s = s.withQuickAction("light.living_room", "toggle", "")
        s = s.withQuickAction("lock.front_door", "unlock", "Unlock door")
        val guard = ServiceCallGuard { s }
        assertEquals("Toggle", guard.check("light.living_room", "toggle").label)
        assertTrue("locks need confirmation", guard.check("lock.front_door", "unlock").requiresConfirmation)
        expectNotAllowed { guard.check("light.living_room", "turn_on") }        // not configured
        expectNotAllowed { guard.check("light.kitchen", "toggle") }             // not a favorite
        expectNotAllowed { s.withQuickAction("light.living_room", "delete_all", "") } // not in catalog
        assertEquals(null, ServiceCatalog.find("alarm_control_panel", "alarm_disarm"))
        // removing the favorite removes its actions
        s = s.withFavorite("lock.front_door", false)
        expectNotAllowed { guard.check("lock.front_door", "unlock") }
    }

    @Test fun `selection persistence revalidates stored actions`() {
        val kv = InMemoryKeyValueStore()
        val store = SelectionStore(kv)
        val s = GlassesSelection().withFavorite("light.living_room", true).withQuickAction("light.living_room", "turn_on", "On")
        store.save(s)
        assertEquals(s, store.load())
        // Tampered storage: an action outside the catalog is dropped on load.
        kv.putString("glasses.selection", kv.getString("glasses.selection")!!.replace("turn_on", "shell_command"))
        assertTrue(store.load().quickActions.isEmpty())
    }

    @Test fun `notification parsing formats`() {
        fun n(state: String, attrs: Map<String, String> = emptyMap()) = HaNotification.fromEntity(
            HaEntity("input_text.rokid_ha_notification", state, kotlinx.serialization.json.JsonObject(attrs.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) })),
            0,
        )
        assertEquals(HaNotification.Priority.MEDIUM, n("Dishwasher completed")!!.priority)
        assertEquals("Kitchen", n("""{"title":"Kitchen","message":"Done","priority":"low"}""")!!.title)
        assertEquals(HaNotification.Priority.LOW, n("""{"message":"Done","priority":"low"}""")!!.priority)
        assertEquals(HaNotification.Priority.HIGH, n("Leak", mapOf("priority" to "high"))!!.priority)
        assertEquals(null, n(""))
        assertEquals(null, n("unavailable"))
    }

    private fun expectNotAllowed(block: () -> Unit) {
        try { block(); fail("expected NotAllowed") } catch (_: HaException.NotAllowed) {}
    }
}
