package tw.rokidha.presentation.interaction

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tw.rokidha.domain.glasses.GlassesInput
import tw.rokidha.domain.glasses.GlassesInput.Kind
import tw.rokidha.homeassistant.HaException
import tw.rokidha.homeassistant.config.AccessToken
import tw.rokidha.homeassistant.config.HaConfig
import tw.rokidha.homeassistant.config.HaServerUrl
import tw.rokidha.homeassistant.connection.Backoff
import tw.rokidha.homeassistant.connection.HaConnectionManager
import tw.rokidha.homeassistant.connection.HaConnectionState
import tw.rokidha.homeassistant.fake.FakeHomeAssistant
import tw.rokidha.homeassistant.fake.eventually
import tw.rokidha.homeassistant.selection.GlassesSelection
import tw.rokidha.homeassistant.selection.QuickAction
import tw.rokidha.homeassistant.selection.ServiceCallGuard
import tw.rokidha.homeassistant.ws.HaWebSocketConnector
import tw.rokidha.presentation.FakeGlassesDisplay
import tw.rokidha.presentation.GlassesDashboardBridge
import tw.rokidha.presentation.notifications.NotificationCenter

/**
 * SIMULATED end-to-end tests of the glasses → phone → Home Assistant → glasses loop:
 * fake glasses input + fake glasses display + real HaConnectionManager against a fake HA
 * WebSocket server. They prove the phone-side pipeline, not the Rokid hardware.
 */
class CommandPipelineTest {
    private val ha = FakeHomeAssistant()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val manager = HaConnectionManager(HaWebSocketConnector(), scope, Backoff(50, 200))
    private val selection = MutableStateFlow(
        GlassesSelection()
            .withFavorite("light.living_room", true)
            .withFavorite("script.movie_mode", true)
            .withQuickAction("light.living_room", "turn_off", "Off")
            .withQuickAction("light.living_room", "turn_on", "On")
            .withQuickAction("script.movie_mode", "turn_on", "Run"),
    )
    private val dispatcher = ActionDispatcher(ServiceCallGuard { selection.value }, manager, manager.entities, manager.state, confirmTimeoutMs = 1_500)
    private val display = FakeGlassesDisplay()
    private val controller = GlassesInteractionController(selection, manager.state, display.state, dispatcher)
    private var seq = 0L

    @Before fun setUp() = runBlocking {
        ha.setState("script.movie_mode", "off", mapOf("friendly_name" to "Movie Mode"))
        manager.start(HaConfig(HaServerUrl.of(ha.baseUrl), AccessToken.parse(FakeHomeAssistant.VALID_TOKEN)!!))
        eventually { manager.state.value is HaConnectionState.Connected }
        display.linkUp()
        controller.enabled.value = true
        controller.start(scope)
        val center = NotificationCenter()
        GlassesDashboardBridge(
            display, manager.state, manager.entities, manager.lastSyncMs, selection, center.all, center::forGlasses,
            interaction = controller.view, minIntervalMs = 10,
        ).start(scope)
        // Wait for the final layout (dashboard + interaction line) so tests start from a stable view.
        eventually { display.state.value.displayOpen && display.allText().contains("> Living Light") }
    }

    @After fun tearDown() {
        manager.stop()
        scope.cancel()
        ha.close()
    }

    private fun input(kind: Kind = Kind.PRIMARY, at: Long = System.currentTimeMillis(), s: Long = ++seq) =
        GlassesInput(kind, GlassesInput.Source.PHONE_SIMULATOR, at, s)

    /** Two presses separated by more than the 400 ms debounce. */
    private fun pressTwice() {
        controller.onInput(input())
        Thread.sleep(450)
        controller.onInput(input())
    }

    private fun lightCalls() = ha.serviceCalls.filter { (it["target"] as? JsonObject)?.get("entity_id") == JsonPrimitive("light.living_room") }

    // 1 + 7
    @Test fun `confirmed glasses input runs the authorized action and the glasses show the HA-confirmed state`() = runBlocking {
        eventually { display.allText().contains("> Living Light: ON") }
        controller.onInput(input())
        eventually { display.allText().contains("Confirm Off · Living Light? Press again") }
        assertTrue("arming alone must not send anything", ha.serviceCalls.isEmpty())
        Thread.sleep(450)
        controller.onInput(input())
        eventually { dispatcher.status.value is CommandStatus.Confirmed }
        assertEquals(1, lightCalls().size)
        assertEquals(JsonPrimitive("turn_off"), lightCalls()[0]["service"])
        eventually(message = "glasses refreshed from HA state") {
            display.updates.any { it["e0"] == "> Living Light: OFF" } && display.updates.any { it["action"] == "OK: Living Light is OFF" }
        }
    }

    @Test fun `stateless script is reported as accepted by HA, not as a state`() = runBlocking {
        controller.onInput(input(Kind.NEXT)); Thread.sleep(450); controller.onInput(input(Kind.NEXT))
        eventually { controller.view.value?.target?.entityId == "script.movie_mode" }
        controller.onInput(input(Kind.SELECT))
        Thread.sleep(450)
        controller.onInput(input(Kind.SELECT))
        eventually { dispatcher.status.value is CommandStatus.Accepted }
        eventually { display.updates.any { it["action"] == "OK: Run · Movie Mode (HA accepted)" } }
    }

    // 7 (external change)
    @Test fun `state changes made elsewhere in HA refresh the glasses`() = runBlocking {
        ha.pushState("light.living_room", "off")
        eventually { display.updates.any { it["e0"] == "> Living Light: OFF" } }
    }

    // 2
    @Test fun `unauthorized entity is rejected and nothing is sent`() = runBlocking {
        val s = dispatcher.dispatch(QuickAction("light.kitchen", "turn_off", "Off", false))
        assertTrue(s is CommandStatus.Rejected)
        assertTrue(ha.serviceCalls.isEmpty())
    }

    // 3
    @Test fun `invalid or unlisted service is rejected`() = runBlocking {
        assertTrue(dispatcher.dispatch(QuickAction("light.living_room", "toggle", "Toggle", false)) is CommandStatus.Rejected)
        assertTrue(dispatcher.dispatch(QuickAction("light.living_room", "delete_everything", "x", false)) is CommandStatus.Rejected)
        try {
            selection.value.withQuickAction("light.living_room", "delete_everything", "x")
            throw AssertionError("catalog must refuse unknown services")
        } catch (_: HaException.NotAllowed) {
        }
        assertTrue(ha.serviceCalls.isEmpty())
    }

    // 4
    @Test fun `duplicate and bouncing events never execute twice`() = runBlocking {
        val first = input()
        controller.onInput(first)
        controller.onInput(first) // same event delivered twice → dropped
        controller.onInput(input()) // second callback for the same press within 400 ms → debounced
        Thread.sleep(200)
        assertTrue("still only armed", ha.serviceCalls.isEmpty())
        Thread.sleep(300)
        val confirm = input()
        controller.onInput(confirm)
        controller.onInput(confirm) // duplicate of the confirming press
        eventually { dispatcher.status.value?.isTerminal == true }
        Thread.sleep(300)
        assertEquals(1, lightCalls().size)
    }

    @Test fun `concurrent commands for the same entity are not both sent`() = runBlocking {
        ha.applyServiceEffects = false // HA never reports the new state → first command stays in flight
        val a = QuickAction("light.living_room", "turn_off", "Off", false)
        val results = listOf(async(Dispatchers.Default) { dispatcher.dispatch(a) }, async(Dispatchers.Default) { dispatcher.dispatch(a) }).awaitAll()
        assertEquals(1, results.count { it is CommandStatus.Rejected })
        assertEquals(1, lightCalls().size)
        assertTrue(results.any { it is CommandStatus.Unconfirmed })
    }

    // 5
    @Test fun `HA disconnected reports failure and sends nothing`() = runBlocking {
        manager.stop()
        val s = dispatcher.dispatch(QuickAction("light.living_room", "turn_off", "Off", false))
        assertTrue(s is CommandStatus.Failed)
        eventually { display.allText().contains("Controls paused: HA offline") }
        assertTrue(ha.serviceCalls.isEmpty())
    }

    // 6
    @Test fun `HA service failure is shown on the glasses`() = runBlocking {
        ha.failingEntities += "light.living_room"
        pressTwice()
        eventually { dispatcher.status.value is CommandStatus.Failed }
        eventually { display.updates.any { it["action"]?.startsWith("Failed:") == true } }
        assertFalse("never shown as success", display.allText().contains("OK: Living Light"))
    }

    @Test fun `unavailable entity is reported and not commanded`() = runBlocking {
        ha.pushState("light.living_room", "unavailable")
        eventually { manager.entities.value["light.living_room"]?.isUnavailable == true }
        pressTwice()
        eventually { dispatcher.status.value is CommandStatus.Unavailable }
        assertTrue(ha.serviceCalls.isEmpty())
        eventually { display.updates.any { it["action"]?.startsWith("Unavailable:") == true } }
    }

    // 8
    @Test fun `losing the glasses does not stop HA synchronisation`() = runBlocking {
        display.mutable.value = display.mutable.value.copy(glassesConnected = false, displayOpen = false)
        ha.pushState("light.living_room", "off")
        eventually { manager.entities.value["light.living_room"]?.state == "off" }
        assertTrue(manager.state.value is HaConnectionState.Connected)
    }

    // 9
    @Test fun `a press from before a reconnect cannot complete a command`() = runBlocking {
        controller.onInput(input())
        eventually { controller.view.value?.armedUntilMs != null }
        ha.dropConnections()
        eventually { controller.view.value?.armedUntilMs == null }
        eventually { manager.state.value is HaConnectionState.Connected && ha.wsAuthOk.get() >= 2 }
        Thread.sleep(450) // outside the debounce window, so the press is accepted (and only re-arms)
        controller.onInput(input()) // would have been the confirming press
        Thread.sleep(500)
        assertTrue("re-armed only, nothing executed", ha.serviceCalls.isEmpty())
        assertTrue(controller.view.value?.armedUntilMs != null)
    }

    @Test fun `stale events are dropped`() = runBlocking {
        controller.onInput(input(at = System.currentTimeMillis() - 10_000))
        Thread.sleep(100)
        assertEquals(null, controller.view.value?.armedUntilMs)
        assertTrue(controller.view.value?.hint?.contains("stale") == true)
    }

    @Test fun `arming times out without sending`() = runBlocking {
        val quick = GlassesInteractionController(selection, manager.state, display.state, dispatcher, armTimeoutMs = 200)
        quick.enabled.value = true
        quick.start(scope)
        quick.onInput(input())
        eventually { quick.view.value?.armedUntilMs != null }
        eventually { quick.view.value?.armedUntilMs == null && quick.view.value?.hint?.contains("Timed out") == true }
        assertTrue(ha.serviceCalls.isEmpty())
    }

    @Test fun `input is ignored while glasses control is disabled`() = runBlocking {
        controller.enabled.value = false
        pressTwice()
        Thread.sleep(300)
        assertTrue(ha.serviceCalls.isEmpty())
    }
}
