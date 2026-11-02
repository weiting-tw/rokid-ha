package tw.rokidha.homeassistant.ws

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import tw.rokidha.homeassistant.HaException
import tw.rokidha.homeassistant.HaHttp
import tw.rokidha.homeassistant.config.HaConfig
import tw.rokidha.homeassistant.model.str
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Opens authenticated sessions to the Home Assistant WebSocket API
 * (https://developers.home-assistant.io/docs/api/websocket/).
 */
class HaWebSocketConnector(
    private val client: OkHttpClient = HaHttp.newClient(),
    private val authTimeoutMs: Long = 10_000,
) {
    /** Connects and completes the auth phase. Throws [HaException] (e.g. [HaException.AuthInvalid]). */
    suspend fun connect(config: HaConfig): HaWsSession {
        val session = HaWsSession()
        val ws = client.newWebSocket(Request.Builder().url(config.url.webSocketUrl).build(), session.listener)
        session.attach(ws)
        try {
            session.authenticate(config.token.value, authTimeoutMs)
        } catch (e: Throwable) {
            ws.cancel()
            throw e
        }
        return session
    }
}

/** One authenticated WebSocket connection. Not reusable after [closed] completes. */
class HaWsSession internal constructor() {

    private val json = Json { ignoreUnknownKeys = true }
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val subscriptions = ConcurrentHashMap<Int, (JsonObject) -> Unit>()
    private val handshake = Channel<JsonObject>(Channel.UNLIMITED)
    @Volatile private var authenticated = false
    private lateinit var ws: WebSocket

    private val _closed = CompletableDeferred<HaException>()

    /** Completes (with the reason) when the connection ends for any reason. */
    val closed: Deferred<HaException> get() = _closed

    /** `ha_version` announced in `auth_required`. */
    @Volatile var haVersion: String? = null
        private set

    internal fun attach(socket: WebSocket) {
        ws = socket
    }

    internal suspend fun authenticate(token: String, timeoutMs: Long) {
        try {
            withTimeout(timeoutMs) {
                val first = handshake.receive()
                if (first.str("type") != "auth_required") throw HaException.Protocol("expected auth_required, got ${first.str("type")}")
                haVersion = first.str("ha_version")
                ws.send(buildJsonObject { put("type", "auth"); put("access_token", token) }.toString())
                val second = handshake.receive()
                when (second.str("type")) {
                    "auth_ok" -> authenticated = true
                    "auth_invalid" -> throw HaException.AuthInvalid(second.str("message"))
                    else -> throw HaException.Protocol("unexpected auth reply ${second.str("type")}")
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw HaException.Protocol("no WebSocket auth response within ${timeoutMs}ms (is this Home Assistant?)")
        }
    }

    /**
     * Sends one command and waits for its `result`. Returns the `result` payload (may be null).
     * Throws [HaException.CommandFailed], [HaException.ConnectionLost] or [HaException.Protocol] on timeout.
     */
    suspend fun command(type: String, fields: Map<String, JsonElement> = emptyMap(), timeoutMs: Long = 15_000): JsonElement? =
        send(type, fields, timeoutMs, onEvent = null)

    /** `subscribe_events` for [eventType]; [onEvent] receives the `event` object on an OkHttp thread. */
    suspend fun subscribeEvents(eventType: String, onEvent: (JsonObject) -> Unit): JsonElement? =
        send("subscribe_events", mapOf("event_type" to JsonPrimitive(eventType)), 15_000, onEvent)

    fun close() {
        if (::ws.isInitialized) ws.close(1000, "client closing")
    }

    private suspend fun send(
        type: String,
        fields: Map<String, JsonElement>,
        timeoutMs: Long,
        onEvent: ((JsonObject) -> Unit)?,
    ): JsonElement? {
        if (!authenticated || _closed.isCompleted) throw HaException.NotConnected()
        val id = nextId.getAndIncrement()
        val result = CompletableDeferred<JsonObject>()
        pending[id] = result
        if (onEvent != null) subscriptions[id] = onEvent
        val msg = JsonObject(fields + mapOf("id" to JsonPrimitive(id), "type" to JsonPrimitive(type)))
        if (!ws.send(msg.toString())) {
            pending.remove(id)
            subscriptions.remove(id)
            throw HaException.ConnectionLost()
        }
        val reply = try {
            withTimeout(timeoutMs) { result.await() }
        } catch (e: TimeoutCancellationException) {
            pending.remove(id)
            subscriptions.remove(id)
            throw HaException.Protocol("no result for '$type' within ${timeoutMs}ms")
        }
        if ((reply["success"] as? JsonPrimitive)?.content == "true") return reply["result"]
        subscriptions.remove(id)
        val err = reply["error"] as? JsonObject
        throw HaException.CommandFailed(err?.str("code"), err?.str("message"))
    }

    private fun dispatch(o: JsonObject) {
        if (!authenticated) {
            handshake.trySend(o)
            return
        }
        val id = (o["id"] as? JsonPrimitive)?.content?.toIntOrNull()
        when (o.str("type")) {
            "result", "pong" -> if (id != null) pending.remove(id)?.complete(o)
            "event" -> if (id != null) (o["event"] as? JsonObject)?.let { ev -> subscriptions[id]?.invoke(ev) }
        }
    }

    private fun finish(reason: HaException) {
        if (!_closed.complete(reason)) return
        authenticated = false
        handshake.close(reason)
        pending.values.forEach { it.completeExceptionally(reason) }
        pending.clear()
        subscriptions.clear()
    }

    internal val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val el = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return
            when (el) {
                is JsonObject -> dispatch(el)
                is JsonArray -> el.forEach { (it as? JsonObject)?.let(::dispatch) } // coalesced messages
                else -> Unit
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            finish(HaException.ConnectionLost())
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish(HaException.ConnectionLost())

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val reason = when {
                response != null && response.code == 401 -> HaException.Unauthorized()
                response != null -> HaException.Protocol("WebSocket upgrade failed with HTTP ${response.code} (wrong URL?)")
                t is IOException -> HaException.fromIo(t)
                else -> HaException.ConnectionLost()
            }
            finish(reason)
        }
    }
}
