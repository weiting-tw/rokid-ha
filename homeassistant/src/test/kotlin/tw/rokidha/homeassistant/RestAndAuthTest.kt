package tw.rokidha.homeassistant

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tw.rokidha.homeassistant.config.AccessToken
import tw.rokidha.homeassistant.config.HaConfig
import tw.rokidha.homeassistant.config.HaServerUrl
import tw.rokidha.homeassistant.fake.FakeHomeAssistant
import tw.rokidha.homeassistant.rest.HaRestClient
import tw.rokidha.homeassistant.setup.HaConnectionTester
import java.net.ServerSocket

/** REST requests, successful authentication, invalid token and unreachable server. */
class RestAndAuthTest {
    private val ha = FakeHomeAssistant()

    @After fun tearDown() = ha.close()

    private fun config(token: String = FakeHomeAssistant.VALID_TOKEN, url: String = ha.baseUrl) =
        HaConfig(HaServerUrl.of(url), AccessToken.parse(token)!!)

    @Test fun `successful authentication and basic REST requests`() = runBlocking {
        val rest = HaRestClient(config())
        assertEquals("API running.", rest.checkApi())
        val info = rest.getConfig()
        assertEquals("Test Home", info.locationName)
        assertEquals(FakeHomeAssistant.VERSION, info.version)
        val states = rest.getStates().associateBy { it.entityId }
        assertEquals("on", states.getValue("light.living_room").state)
        assertEquals("Living Light", states.getValue("light.living_room").friendlyName)
        assertEquals("26.0", rest.getState("sensor.indoor_temperature")?.state)
        assertNull(rest.getState("sensor.missing"))
        rest.callService("light", "turn_off")
        assertEquals("light", ha.serviceCalls.last()["domain"].toString().trim('"'))
        assertTrue(ha.restRequests.all { it.getHeader("Authorization") == "Bearer ${FakeHomeAssistant.VALID_TOKEN}" })
    }

    @Test fun `invalid token is reported as Unauthorized`() = runBlocking {
        try {
            HaRestClient(config(token = "wrong-token")).checkApi()
            fail("expected Unauthorized")
        } catch (e: HaException.Unauthorized) {
            assertTrue(e.isCredentialProblem)
            assertTrue("message must not echo the token", !e.userMessage.contains("wrong-token"))
        }
    }

    @Test fun `unreachable server is reported as Unreachable`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort } // free port, nothing listening
        try {
            HaRestClient(config(url = "http://127.0.0.1:$port")).checkApi()
            fail("expected Unreachable")
        } catch (e: HaException.Unreachable) {
            assertTrue(!e.isCredentialProblem)
        }
    }

    @Test fun `redirects are refused so the token is never forwarded`() = runBlocking {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "http://evil.example.com/api/"))
            s.start()
            try {
                HaRestClient(config(url = s.url("/").toString())).checkApi()
                fail("expected Protocol")
            } catch (e: HaException.Protocol) {
                assertEquals(1, s.requestCount)
            }
        }
    }

    @Test fun `non-HA server answering HTML is a protocol error`() = runBlocking {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setBody("<html>router login</html>"))
            s.start()
            try {
                HaRestClient(config(url = s.url("/").toString())).checkApi()
                fail("expected Protocol")
            } catch (_: HaException.Protocol) {
            }
        }
    }

    @Test fun `connection tester validates REST and WebSocket`() = runBlocking {
        val ok = HaConnectionTester().test(config()) as HaConnectionTester.Result.Ok
        assertEquals("Test Home", ok.locationName)
        assertEquals(1, ha.wsAuthOk.get())
        val bad = HaConnectionTester().test(config(token = "nope")) as HaConnectionTester.Result.Failed
        assertTrue(bad.error.isCredentialProblem)
    }
}
