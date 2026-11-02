package tw.rokidha.presentation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.rokidha.domain.glasses.DisplayScreen
import tw.rokidha.domain.glasses.GlassesDisplayPort
import tw.rokidha.domain.glasses.GlassesLinkState
import tw.rokidha.homeassistant.config.AccessToken
import tw.rokidha.homeassistant.config.HaConfig
import tw.rokidha.homeassistant.config.HaServerUrl
import tw.rokidha.homeassistant.connection.Backoff
import tw.rokidha.homeassistant.connection.HaConnectionManager
import tw.rokidha.homeassistant.connection.HaConnectionState
import tw.rokidha.homeassistant.fake.FakeHomeAssistant
import tw.rokidha.homeassistant.fake.eventually
import tw.rokidha.homeassistant.model.HaEntity
import tw.rokidha.homeassistant.notifications.HaNotification
import tw.rokidha.homeassistant.selection.GlassesSelection
import tw.rokidha.homeassistant.ws.HaWebSocketConnector
import tw.rokidha.presentation.dashboard.DashboardModel
import tw.rokidha.presentation.dashboard.DashboardRenderer
import tw.rokidha.presentation.dashboard.EntityFormatter
import tw.rokidha.presentation.notifications.NotificationCenter
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList

/** Records everything that would be sent to the glasses; simulates the CustomView callbacks. */
class FakeGlassesDisplay : GlassesDisplayPort {
    val mutable = MutableStateFlow(GlassesLinkState())
    override val state: StateFlow<GlassesLinkState> = mutable
    val shown = CopyOnWriteArrayList<DisplayScreen>()
    val updates = CopyOnWriteArrayList<Map<String, String>>()
    var hides = 0

    fun linkUp() { mutable.value = mutable.value.copy(transportConnected = true, glassesConnected = true) }

    override fun show(screen: DisplayScreen): Boolean {
        if (!mutable.value.linkReady || mutable.value.displayOpen) return false
        shown += screen
        mutable.value = mutable.value.copy(displayOpen = true) // onCustomViewOpened
        return true
    }

    override fun updateText(changes: Map<String, String>): Boolean {
        if (!mutable.value.displayOpen) return false
        updates += changes
        return true
    }

    override fun hide(): Boolean {
        hides++
        mutable.value = mutable.value.copy(displayOpen = false) // onCustomViewClosed
        return true
    }

    /** Every string that reached the "glasses". */
    fun allText(): String = (shown.flatMap { s -> s.lines.map { it.text } + s.lines.map { it.id } + s.id } + updates.flatMap { it.values + it.keys })
        .joinToString("\n")
}

class GlassesDashboardBridgeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val display = FakeGlassesDisplay()
    private val haState = MutableStateFlow<HaConnectionState>(HaConnectionState.Connected("2026.10.3", 0))
    private val entities = MutableStateFlow(
        mapOf(
            "light.living_room" to entity("light.living_room", "on", "Living Light"),
            "sensor.temp" to entity("sensor.temp", "26.0", "Indoor", "unit_of_measurement" to "°C"),
        ),
    )
    private val lastSync = MutableStateFlow<Long?>(1_000L)
    private val selection = MutableStateFlow(GlassesSelection(favorites = listOf("light.living_room", "sensor.temp")))
    private val center = NotificationCenter()
    private val bridge = GlassesDashboardBridge(
        display, haState, entities, lastSync, selection, center.all, center::forGlasses,
        renderer = DashboardRenderer(TimeZone.getTimeZone("UTC")),
        clock = { 2_000L }, minIntervalMs = 10,
    )

    @After fun tearDown() = scope.cancel()

    private fun entity(id: String, state: String, name: String, vararg attrs: Pair<String, String>) =
        HaEntity(id, state, JsonObject((attrs.toList() + ("friendly_name" to name)).associate { it.first to JsonPrimitive(it.second) }))

    @Test fun `dashboard opens automatically once the glasses link is ready`() = runBlocking {
        bridge.start(scope)
        Thread.sleep(150)
        assertTrue("nothing before link ready", display.shown.isEmpty())
        display.linkUp()
        eventually { display.shown.size == 1 }
        val texts = display.shown[0].lines.map { it.text }
        assertTrue(texts.contains("HA ONLINE"))
        assertTrue(texts.contains("Living Light: ON"))
        assertTrue(texts.contains("Indoor: 26.0 °C"))
    }

    @Test fun `state changes are sent as incremental updates of changed lines only`() = runBlocking {
        bridge.start(scope)
        display.linkUp()
        eventually { display.shown.size == 1 }
        entities.value = entities.value + ("light.living_room" to entity("light.living_room", "off", "Living Light"))
        eventually { display.updates.any { it["e0"] == "Living Light: OFF" } }
        val u = display.updates.first { it.containsKey("e0") }
        assertFalse("unchanged lines are not resent", u.containsKey("e1"))
        assertEquals(1, display.shown.size)
    }

    @Test fun `offline is shown clearly and cached values are not presented as current`() = runBlocking {
        bridge.start(scope)
        display.linkUp()
        eventually { display.shown.size == 1 }
        haState.value = HaConnectionState.Retrying("lost", 1, 5_000)
        eventually { display.updates.any { it["ha"] == "HA OFFLINE · reconnecting" } }
        eventually { display.updates.any { it["e0"] == "~ Living Light: ON" } }
        haState.value = HaConnectionState.Stopped
        eventually { display.updates.any { it["e0"] == "Living Light: —" } }
    }

    @Test fun `changing favorites re-opens the view with the new layout`() = runBlocking {
        bridge.start(scope)
        display.linkUp()
        eventually { display.shown.size == 1 }
        selection.value = GlassesSelection(favorites = listOf("sensor.temp"))
        eventually { display.shown.size == 2 }
        assertEquals(1, display.hides)
        assertFalse(display.shown[1].lines.any { it.text.startsWith("Living Light") })
    }

    @Test fun `low priority notifications never occupy the glasses, high ones do`() = runBlocking {
        bridge.start(scope)
        display.linkUp()
        eventually { display.shown.size == 1 }
        center.add(HaNotification("1", null, "Status update", HaNotification.Priority.LOW, 1_500))
        Thread.sleep(150)
        assertFalse(display.allText().contains("Status update"))
        center.add(HaNotification("2", "Kitchen", "Water leak", HaNotification.Priority.HIGH, 1_500))
        eventually { display.updates.any { it["notice"] == "! Kitchen: Water leak" } }
    }

    @Test fun `no favorites shows a hint instead of every entity`() {
        val s = DashboardRenderer().render(DashboardModel(haState.value, emptyList(), entities.value, 1L, null))
        assertTrue(s.lines.none { it.text.contains("Living Light") })
        assertTrue(s.lines.any { it.text.contains("phone app") })
    }

    @Test fun `formatter covers common domains and unavailable states`() {
        assertEquals("OPEN", EntityFormatter.value(entity("binary_sensor.door", "on", "Door", "device_class" to "door")))
        assertEquals("COOL 26°", EntityFormatter.value(entity("climate.ac", "cool", "AC", "temperature" to "26")))
        assertEquals("UNAVAILABLE", EntityFormatter.value(entity("light.x", "unavailable", "X")))
        assertEquals("UNKNOWN", EntityFormatter.value(entity("sensor.x", "unknown", "X")))
        assertEquals("LOCKED", EntityFormatter.value(entity("lock.front", "locked", "Front")))
    }
}

/** Full pipeline: fake HA → connection manager → bridge → fake glasses. */
class GlassesCredentialIsolationTest {
    private val ha = FakeHomeAssistant()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() {
        scope.cancel()
        ha.close()
    }

    @Test fun `glasses receive live data but never the token or server address`() = runBlocking {
        val manager = HaConnectionManager(HaWebSocketConnector(), scope, Backoff(50, 200))
        val center = NotificationCenter()
        scope.launch { manager.notifications.collect(center::add) }
        val display = FakeGlassesDisplay()
        val bridge = GlassesDashboardBridge(
            display, manager.state, manager.entities, manager.lastSyncMs,
            MutableStateFlow(GlassesSelection(favorites = listOf("light.living_room", "sensor.indoor_temperature"))),
            center.all, center::forGlasses, minIntervalMs = 10,
        )
        val url = HaServerUrl.of(ha.baseUrl)
        manager.start(HaConfig(url, AccessToken.parse(FakeHomeAssistant.VALID_TOKEN)!!))
        bridge.start(scope)
        display.linkUp()
        eventually { display.shown.isNotEmpty() && display.allText().contains("Living Light: ON") }
        ha.pushState("light.living_room", "off")
        eventually { display.allText().contains("Living Light: OFF") }
        ha.pushState(HaNotification.DEFAULT_ENTITY, "high|Front door|Door opened")
        eventually { display.allText().contains("Door opened") }

        val everything = display.allText()
        assertFalse(everything.contains(FakeHomeAssistant.VALID_TOKEN))
        assertFalse(everything.contains("SECRET_SIGNATURE"))
        assertFalse(everything.contains(url.host + ":" + url.base.port))
        assertFalse(everything.contains("127.0.0.1"))
        manager.stop()
    }
}
