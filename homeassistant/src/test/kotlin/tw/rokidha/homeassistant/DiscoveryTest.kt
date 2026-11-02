package tw.rokidha.homeassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.rokidha.homeassistant.discovery.ZeroconfParser

/** mDNS / DNS-SD: TXT record parsing and merging (the NsdManager side is Android-only). */
class DiscoveryTest {

    private val txt = mapOf(
        "location_name" to "Home",
        "uuid" to "0123abcd",
        "version" to "2026.10.3",
        "internal_url" to "http://192.168.1.10:8123",
        "external_url" to "https://ha.example.com",
        "base_url" to "http://192.168.1.10:8123",
        "requires_api_password" to "True",
    )

    @Test fun `parses home assistant txt record into ordered candidates`() {
        val i = ZeroconfParser.parse("Home._home-assistant._tcp.local.", listOf("fe80::1%wlan0", "192.168.1.10"), 8123, txt)
        assertEquals("0123abcd", i.uuid)
        assertEquals("Home", i.displayName)
        assertEquals("2026.10.3", i.version)
        assertEquals(
            listOf("http://192.168.1.10:8123", "http://[fe80::1]:8123", "https://ha.example.com"),
            i.candidates.map { it.toString() },
        )
    }

    @Test fun `remote plain-http urls advertised in txt are dropped`() {
        val i = ZeroconfParser.parse("x", emptyList(), 8123, mapOf("external_url" to "http://ha.example.com"))
        assertTrue(i.candidates.isEmpty())
    }

    @Test fun `missing txt still yields host address candidate`() {
        val i = ZeroconfParser.parse("Home Assistant", listOf("192.168.1.20"), 8123, emptyMap())
        assertEquals(listOf("http://192.168.1.20:8123"), i.candidates.map { it.toString() })
        assertEquals("Home Assistant", i.key)
    }

    @Test fun `repeated sightings merge by uuid`() {
        val a = ZeroconfParser.parse("Home", listOf("192.168.1.10"), 8123, mapOf("uuid" to "u1"))
        val b = ZeroconfParser.parse("Home (2)", listOf("fd00::10"), 8123, mapOf("uuid" to "u1", "location_name" to "Home"))
        val merged = ZeroconfParser.merge(ZeroconfParser.merge(emptyList(), a), b)
        assertEquals(1, merged.size)
        assertEquals("Home", merged[0].locationName)
        assertEquals(2, merged[0].candidates.size)
    }
}
