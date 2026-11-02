package tw.rokidha.homeassistant

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tw.rokidha.homeassistant.config.AccessToken
import tw.rokidha.homeassistant.config.HaConfig
import tw.rokidha.homeassistant.config.HaServerUrl
import tw.rokidha.homeassistant.fake.FakeHomeAssistant
import tw.rokidha.homeassistant.fake.eventually
import tw.rokidha.homeassistant.ws.HaWebSocketConnector
import java.util.concurrent.CopyOnWriteArrayList

/** WebSocket authentication and subscription. */
class WebSocketTest {
    private val ha = FakeHomeAssistant()

    @After fun tearDown() = ha.close()

    private fun config(token: String = FakeHomeAssistant.VALID_TOKEN) =
        HaConfig(HaServerUrl.of(ha.baseUrl), AccessToken.parse(token)!!)

    @Test fun `authenticates, gets states and receives subscribed events`() = runBlocking {
        val s = HaWebSocketConnector().connect(config())
        assertEquals(FakeHomeAssistant.VERSION, s.haVersion)
        val states = s.command("get_states") as JsonArray
        assertEquals(2, states.size)
        val events = CopyOnWriteArrayList<JsonObject>()
        s.subscribeEvents("state_changed") { events += it }
        ha.pushState("light.living_room", "off")
        eventually { events.isNotEmpty() }
        val data = events.first()["data"] as JsonObject
        assertEquals("light.living_room", (data["entity_id"] as JsonPrimitive).content)
        s.close()
    }

    @Test fun `invalid token yields AuthInvalid`() = runBlocking {
        try {
            HaWebSocketConnector().connect(config(token = "bad"))
            fail("expected AuthInvalid")
        } catch (e: HaException.AuthInvalid) {
            assertTrue(e.isCredentialProblem)
        }
    }

    @Test fun `refused subscription surfaces as CommandFailed (non-admin custom event)`() = runBlocking {
        val s = HaWebSocketConnector().connect(config())
        try {
            s.subscribeEvents("my_custom_event") {}
            fail("expected CommandFailed")
        } catch (e: HaException.CommandFailed) {
            assertEquals("unauthorized", e.code)
        }
        s.close()
    }

    @Test fun `pending command fails with ConnectionLost when the socket drops`() = runBlocking {
        val s = HaWebSocketConnector().connect(config())
        ha.dropConnections()
        val reason = s.closed.await()
        assertTrue(reason is HaException.ConnectionLost || reason is HaException.Unreachable)
        try {
            s.command("get_states")
            fail("expected failure")
        } catch (_: HaException) {
        }
    }
}
