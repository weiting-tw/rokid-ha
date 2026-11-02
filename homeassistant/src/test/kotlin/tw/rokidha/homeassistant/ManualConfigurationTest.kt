package tw.rokidha.homeassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.rokidha.homeassistant.config.AccessToken
import tw.rokidha.homeassistant.config.HaConfig
import tw.rokidha.homeassistant.config.HaServerUrl
import tw.rokidha.homeassistant.config.HaServerUrl.ParseResult

/** Manual server configuration: URL normalisation and transport policy. */
class ManualConfigurationTest {

    private fun ok(input: String) = (HaServerUrl.parse(input) as ParseResult.Ok).url
    private fun invalid(input: String) = HaServerUrl.parse(input) as ParseResult.Invalid

    @Test fun `local http urls are accepted as typed`() {
        assertEquals("http://homeassistant.local:8123", ok("http://homeassistant.local:8123").toString())
        assertEquals("http://192.168.1.10:8123", ok(" http://192.168.1.10:8123/ ").toString())
        assertEquals("http://10.0.0.2:8123", ok("http://10.0.0.2:8123").toString())
        assertEquals("http://homeassistant:8123", ok("http://homeassistant:8123").toString())
        assertEquals("http://100.101.102.103:8123", ok("http://100.101.102.103:8123").toString()) // Tailscale CGNAT
    }

    @Test fun `missing scheme defaults to http only for local hosts`() {
        assertEquals("http://192.168.1.10:8123", ok("192.168.1.10:8123").toString())
        assertEquals("https://ha.example.com", ok("ha.example.com").toString())
    }

    @Test fun `remote plain http is rejected, never silently changed`() {
        assertTrue(invalid("http://ha.example.com").reason.contains("https"))
        assertTrue(invalid("http://8.8.8.8:8123").reason.contains("https"))
    }

    @Test fun `https is kept for local hosts too`() {
        val u = ok("https://192.168.1.10:8123")
        assertTrue(u.isHttps)
        assertEquals("wss://192.168.1.10:8123/api/websocket", u.webSocketUrl)
    }

    @Test fun `websocket url follows scheme`() {
        assertEquals("ws://homeassistant.local:8123/api/websocket", ok("http://homeassistant.local:8123").webSocketUrl)
        assertEquals("wss://ha.example.com/api/websocket", ok("https://ha.example.com").webSocketUrl)
    }

    @Test fun `paths and queries are stripped with a warning`() {
        val r = HaServerUrl.parse("http://homeassistant.local:8123/lovelace/0?edit=1") as ParseResult.Ok
        assertEquals("http://homeassistant.local:8123", r.url.toString())
        assertTrue(r.warnings.any { "removed" in it })
    }

    @Test fun `garbage, other schemes and embedded credentials are rejected`() {
        invalid("")
        invalid("ftp://homeassistant.local")
        invalid("http://user:pass@homeassistant.local:8123")
        invalid("http://")
    }

    @Test fun `public hostnames resembling private ones are not local`() {
        assertFalse(HaServerUrl.isLocalHost("192.168.1.10.example.com"))
        assertFalse(HaServerUrl.isLocalHost("local.example.com"))
        assertTrue(HaServerUrl.isLocalHost("fe80::1"))
        assertTrue(HaServerUrl.isLocalHost("fd12:3456::1"))
    }

    @Test fun `token parsing strips Bearer and whitespace, and toString never reveals it`() {
        val t = AccessToken.parse("  Bearer abc.def.ghi \n")!!
        assertEquals("abc.def.ghi", t.value)
        assertFalse(t.toString().contains("abc"))
        val cfg = HaConfig(ok("http://homeassistant.local:8123"), t)
        assertFalse("data class toString must not leak", cfg.toString().contains("abc.def.ghi"))
        assertNull(AccessToken.parse("   "))
        assertNull(AccessToken.parse("abc def"))
    }
}
