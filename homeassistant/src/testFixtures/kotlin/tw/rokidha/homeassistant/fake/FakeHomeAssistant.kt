package tw.rokidha.homeassistant.fake

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scripted Home Assistant for tests: REST (`/api/`, `/api/config`, `/api/states`,
 * `/api/services`) and WebSocket (`auth`, `get_states`, `subscribe_events`, `call_service`,
 * registry lists) on a local MockWebServer. Mimics a **non-admin** user: only
 * `state_changed` may be subscribed.
 */
class FakeHomeAssistant(@Volatile var validToken: String = VALID_TOKEN) : Closeable {

    val server = MockWebServer()
    private val json = Json
    private val states = ConcurrentHashMap<String, JsonObject>()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val subscriptions = ConcurrentHashMap<WebSocket, MutableList<Int>>()

    val serviceCalls = CopyOnWriteArrayList<JsonObject>()
    val restRequests = CopyOnWriteArrayList<RecordedRequest>()
    val wsAuthAttempts = AtomicInteger()
    val wsAuthOk = AtomicInteger()
    val getStatesCount = AtomicInteger()
    /** Every text frame received over the WebSocket, for leak checks. */
    val wsFramesFromClient = CopyOnWriteArrayList<String>()

    /** call_service on these entities returns an error result (simulates a failing device). */
    val failingEntities: MutableSet<String> = java.util.Collections.newSetFromMap(ConcurrentHashMap())

    /** Like real HA: successful on/off/toggle/lock calls change the state and push state_changed. */
    @Volatile var applyServiceEffects: Boolean = true

    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    init {
        setState("light.living_room", "on", mapOf("friendly_name" to "Living Light"))
        setState("sensor.indoor_temperature", "26.0", mapOf("friendly_name" to "Indoor", "unit_of_measurement" to "°C"))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handle(request)
        }
        server.start()
    }

    fun setState(entityId: String, state: String, attributes: Map<String, String> = emptyMap()) {
        states[entityId] = entityJson(entityId, state, attributes)
    }

    /** Changes a state and pushes `state_changed` to subscribed clients. */
    fun pushState(entityId: String, state: String, attributes: Map<String, String> = emptyMap()) {
        val old = states[entityId]
        val new = entityJson(entityId, state, attributes.ifEmpty { attrsOf(old) })
        states[entityId] = new
        val data = buildJsonObject {
            put("entity_id", entityId)
            put("old_state", old ?: JsonPrimitive(null as String?))
            put("new_state", new)
        }
        subscriptions.forEach { (ws, ids) ->
            ids.forEach { id ->
                ws.send(
                    buildJsonObject {
                        put("id", id); put("type", "event")
                        put("event", buildJsonObject { put("event_type", "state_changed"); put("data", data) })
                    }.toString(),
                )
            }
        }
    }

    private fun applyEffect(entityId: String, service: String) {
        val cur = (states[entityId]?.get("state") as? JsonPrimitive)?.content ?: return
        val next = when (service) {
            "turn_on" -> "on"
            "turn_off" -> "off"
            "toggle" -> if (cur == "on") "off" else "on"
            "lock" -> "locked"
            "unlock" -> "unlocked"
            else -> return
        }
        pushState(entityId, next)
    }

    /** Closes every WebSocket with 1012 (as Home Assistant does when it restarts). */
    fun dropConnections() {
        sockets.forEach { it.close(1012, "restart") }
        sockets.clear()
        subscriptions.clear()
    }

    val openSocketCount: Int get() = sockets.size

    override fun close() {
        dropConnections()
        server.shutdown()
    }

    private fun handle(r: RecordedRequest): MockResponse {
        val path = r.path ?: "/"
        if (path == "/api/websocket") return MockResponse().withWebSocketUpgrade(wsListener)
        restRequests += r
        if (r.getHeader("Authorization") != "Bearer $validToken") return MockResponse().setResponseCode(401).setBody("401: Unauthorized")
        return when {
            path == "/api/" -> ok("""{"message":"API running."}""")
            path == "/api/config" -> ok("""{"location_name":"Test Home","version":"$VERSION"}""")
            path == "/api/states" -> ok(JsonArray(states.values.toList()).toString())
            path.startsWith("/api/states/") -> states[path.removePrefix("/api/states/")]?.let { ok(it.toString()) }
                ?: MockResponse().setResponseCode(404)
            path.startsWith("/api/services/") && r.method == "POST" -> {
                val (d, s) = path.removePrefix("/api/services/").split('/')
                serviceCalls += buildJsonObject { put("domain", d); put("service", s); put("via", "rest") }
                ok("[]")
            }
            else -> MockResponse().setResponseCode(404)
        }
    }

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            sockets += webSocket
            webSocket.send("""{"type":"auth_required","ha_version":"$VERSION"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            wsFramesFromClient += text
            val m = json.parseToJsonElement(text) as JsonObject
            val type = (m["type"] as JsonPrimitive).content
            val id = (m["id"] as? JsonPrimitive)?.content?.toInt()
            when (type) {
                "auth" -> {
                    wsAuthAttempts.incrementAndGet()
                    if ((m["access_token"] as? JsonPrimitive)?.content == validToken) {
                        wsAuthOk.incrementAndGet()
                        webSocket.send("""{"type":"auth_ok","ha_version":"$VERSION"}""")
                    } else {
                        webSocket.send("""{"type":"auth_invalid","message":"Invalid access token or password"}""")
                        webSocket.close(1000, null)
                    }
                }
                "subscribe_events" -> {
                    val ev = (m["event_type"] as? JsonPrimitive)?.content
                    if (ev == "state_changed") {
                        subscriptions.getOrPut(webSocket) { CopyOnWriteArrayList() } += id!!
                        result(webSocket, id, null)
                    } else {
                        error(webSocket, id!!, "unauthorized", "Unauthorized")
                    }
                }
                "get_states" -> {
                    getStatesCount.incrementAndGet()
                    result(webSocket, id!!, JsonArray(states.values.toList()))
                }
                "call_service" -> {
                    serviceCalls += m
                    val target = ((m["target"] as? JsonObject)?.get("entity_id") as? JsonPrimitive)?.content
                    if (target != null && target in failingEntities) {
                        error(webSocket, id!!, "home_assistant_error", "Device did not respond")
                    } else {
                        result(webSocket, id!!, buildJsonObject { put("context", buildJsonObject { put("id", "ctx") }) })
                        if (applyServiceEffects && target != null) applyEffect(target, (m["service"] as JsonPrimitive).content)
                    }
                }
                "config/area_registry/list" -> result(webSocket, id!!, json.parseToJsonElement("""[{"area_id":"living","name":"Living Room"}]"""))
                "config/device_registry/list" -> result(webSocket, id!!, JsonArray(emptyList()))
                "config/entity_registry/list" -> result(webSocket, id!!, json.parseToJsonElement("""[{"entity_id":"light.living_room","area_id":"living"}]"""))
                "ping" -> webSocket.send("""{"id":$id,"type":"pong"}""")
                else -> error(webSocket, id ?: 0, "unknown_command", "Unknown command.")
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            sockets -= webSocket
            subscriptions.remove(webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            sockets -= webSocket
            subscriptions.remove(webSocket)
        }
    }

    private fun result(ws: WebSocket, id: Int, result: JsonElement?) {
        ws.send(
            buildJsonObject {
                put("id", id); put("type", "result"); put("success", true)
                put("result", result ?: JsonPrimitive(null as String?))
            }.toString(),
        )
    }

    private fun error(ws: WebSocket, id: Int, code: String, message: String) {
        ws.send(
            buildJsonObject {
                put("id", id); put("type", "result"); put("success", false)
                put("error", buildJsonObject { put("code", code); put("message", message) })
            }.toString(),
        )
    }

    private fun ok(body: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    private fun attrsOf(o: JsonObject?): Map<String, String> =
        (o?.get("attributes") as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.content.orEmpty() }.orEmpty()

    private fun entityJson(entityId: String, state: String, attributes: Map<String, String>) = buildJsonObject {
        put("entity_id", entityId)
        put("state", state)
        put("attributes", buildJsonObject { attributes.forEach { (k, v) -> put(k, v) } })
        put("last_changed", "2026-11-02T00:00:${(System.nanoTime() % 60).toString().padStart(2, '0')}+00:00")
        put("last_updated", "2026-11-02T00:00:00+00:00")
    }

    companion object {
        const val VALID_TOKEN = "eyJ0eXAiOiJKV1QifQ.test-long-lived-token.SECRET_SIGNATURE"
        const val VERSION = "2026.10.3"
    }
}
