package tw.rokidha.homeassistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tw.rokidha.homeassistant.config.AccessToken
import tw.rokidha.homeassistant.config.HaConfig
import tw.rokidha.homeassistant.config.HaServerUrl
import tw.rokidha.homeassistant.connection.Backoff
import tw.rokidha.homeassistant.connection.HaConnectionManager
import tw.rokidha.homeassistant.connection.HaConnectionState
import tw.rokidha.homeassistant.fake.FakeHomeAssistant
import tw.rokidha.homeassistant.fake.eventually
import tw.rokidha.homeassistant.notifications.HaNotification
import tw.rokidha.homeassistant.ws.HaWebSocketConnector
import java.net.ServerSocket
import kotlin.random.Random

/** Subscriptions, reconnection with backoff, no command replay, credential rejection. */
class ConnectionManagerTest {
    private val ha = FakeHomeAssistant()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val manager = HaConnectionManager(
        HaWebSocketConnector(),
        scope,
        Backoff(initialMs = 50, maxMs = 200, random = Random(1)),
    )

    @After fun tearDown() {
        manager.stop()
        scope.cancel()
        ha.close()
    }

    private fun config(token: String = FakeHomeAssistant.VALID_TOKEN, url: String = ha.baseUrl) =
        HaConfig(HaServerUrl.of(url), AccessToken.parse(token)!!)

    private fun connected() = manager.state.value is HaConnectionState.Connected

    @Test fun `connects, syncs the entity cache and follows state_changed`() = runBlocking {
        manager.start(config())
        eventually { connected() }
        assertEquals("on", manager.entities.value.getValue("light.living_room").state)
        assertEquals("Living Room", manager.areas.value["light.living_room"])
        ha.pushState("light.living_room", "off")
        eventually { manager.entities.value["light.living_room"]?.state == "off" }
    }

    @Test fun `reconnects after a drop, re-authenticates, resubscribes and resyncs`() = runBlocking {
        manager.start(config())
        eventually { connected() }
        ha.dropConnections()
        eventually { !connected() }
        // While disconnected HA changes state; the resync must pick it up.
        ha.setState("light.living_room", "off", mapOf("friendly_name" to "Living Light"))
        eventually(message = "reconnect") { connected() && ha.wsAuthOk.get() >= 2 }
        eventually { manager.entities.value["light.living_room"]?.state == "off" }
        assertTrue("get_states after reconnect", ha.getStatesCount.get() >= 2)
        ha.pushState("light.living_room", "on")
        eventually(message = "resubscribed") { manager.entities.value["light.living_room"]?.state == "on" }
    }

    @Test fun `commands are never queued or replayed after reconnect`() = runBlocking {
        manager.start(config())
        eventually { connected() }
        manager.callService("light", "turn_off", "light.living_room")
        assertEquals(1, ha.serviceCalls.size)

        ha.dropConnections()
        eventually { !connected() }
        try {
            manager.callService("light", "turn_on", "light.living_room")
            fail("offline call must fail immediately")
        } catch (_: HaException.NotConnected) {
        }
        eventually { connected() && ha.wsAuthOk.get() >= 2 }
        Thread.sleep(300)
        assertEquals("nothing replayed", 1, ha.serviceCalls.size)
    }

    @Test fun `invalid token stops retrying with CredentialsRejected`() = runBlocking {
        manager.start(config(token = "invalid"))
        val s = withTimeout(5_000) { manager.state.first { it is HaConnectionState.CredentialsRejected } }
        assertTrue(s is HaConnectionState.CredentialsRejected)
        val attempts = ha.wsAuthAttempts.get()
        Thread.sleep(400)
        assertEquals("no automatic retries with a rejected token", attempts, ha.wsAuthAttempts.get())
    }

    @Test fun `revoked token is detected on reconnect`() = runBlocking {
        manager.start(config())
        eventually { connected() }
        ha.validToken = "rotated" // user revoked the token in HA
        ha.dropConnections()
        withTimeout(5_000) { manager.state.first { it is HaConnectionState.CredentialsRejected } }
        Unit
    }

    @Test fun `unreachable server retries with backoff and retryNow reconnects`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        manager.start(config(url = "http://127.0.0.1:$port"))
        val r = withTimeout(5_000) { manager.state.first { it is HaConnectionState.Retrying } } as HaConnectionState.Retrying
        assertTrue(r.reason.contains("reach"))
        manager.start(config()) // user fixes URL
        eventually { connected() }
        manager.stop()
        assertEquals(HaConnectionState.Stopped, manager.state.value)
    }

    @Test fun `notification entity changes become notifications`() = runBlocking {
        val received = mutableListOf<HaNotification>()
        val job = scope.launch { manager.notifications.collect { received += it } }
        ha.setState(HaNotification.DEFAULT_ENTITY, "")
        manager.start(config())
        eventually { connected() }
        ha.pushState(HaNotification.DEFAULT_ENTITY, "high|Front door|Door opened")
        eventually { received.isNotEmpty() }
        assertEquals(HaNotification.Priority.HIGH, received[0].priority)
        assertEquals("Front door", received[0].title)
        assertEquals("Door opened", received[0].message)
        job.cancel()
    }

    @Test fun `reset clears cached data`() = runBlocking {
        manager.start(config())
        eventually { connected() }
        manager.reset()
        assertEquals(HaConnectionState.NotConfigured, manager.state.value)
        assertTrue(manager.entities.value.isEmpty())
    }
}
