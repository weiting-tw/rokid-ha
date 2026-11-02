package tw.rokidha.homeassistant.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tw.rokidha.homeassistant.HaException
import tw.rokidha.homeassistant.config.HaConfig
import tw.rokidha.homeassistant.model.HaEntity
import tw.rokidha.homeassistant.model.str
import tw.rokidha.homeassistant.notifications.HaNotification
import tw.rokidha.homeassistant.ws.HaWebSocketConnector
import tw.rokidha.homeassistant.ws.HaWsSession

/**
 * Keeps one authenticated WebSocket session alive and mirrors Home Assistant state locally.
 *
 * - Reconnects with [Backoff] after any transient failure; [retryNow] skips the wait
 *   (call it when the network comes back or the app returns to the foreground).
 * - After every (re)connect: subscribe `state_changed` → `get_states` (full resync) →
 *   best-effort area lookup. Subscribing first means no change is lost in between.
 * - Credential problems (invalid / expired / revoked token) stop the loop in
 *   [HaConnectionState.CredentialsRejected]; retrying cannot fix them.
 * - **Commands are never queued or replayed.** [callService] fails immediately when not
 *   connected, and a command in flight when the connection drops fails with
 *   [HaException.ConnectionLost] (outcome unknown); the user decides whether to retry.
 * - Home Assistant automations stay in Home Assistant: this class only reads state and
 *   forwards explicitly requested service calls.
 */
class HaConnectionManager(
    private val connector: HaWebSocketConnector,
    private val scope: CoroutineScope,
    private val backoff: Backoff = Backoff(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ServiceCaller {
    private val _state = MutableStateFlow<HaConnectionState>(HaConnectionState.NotConfigured)
    val state: StateFlow<HaConnectionState> = _state.asStateFlow()

    private val _entities = MutableStateFlow<Map<String, HaEntity>>(emptyMap())
    /** Local entity cache. Retained while disconnected; check [state]/[lastSyncMs] for freshness. */
    val entities: StateFlow<Map<String, HaEntity>> = _entities.asStateFlow()

    private val _areas = MutableStateFlow<Map<String, String>>(emptyMap())
    /** entity_id → area name, best effort (registry access may be refused). */
    val areas: StateFlow<Map<String, String>> = _areas.asStateFlow()

    private val _lastSyncMs = MutableStateFlow<Long?>(null)
    /** Time of the last data received from Home Assistant. */
    val lastSyncMs: StateFlow<Long?> = _lastSyncMs.asStateFlow()

    private val _notifications = MutableSharedFlow<HaNotification>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val notifications: SharedFlow<HaNotification> = _notifications.asSharedFlow()

    /** Entity whose state changes are turned into [notifications]. */
    @Volatile var notificationEntityId: String = HaNotification.DEFAULT_ENTITY

    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var job: Job? = null
    @Volatile private var session: HaWsSession? = null

    /** Starts (or restarts with new [config]) the connection loop. */
    @Synchronized
    fun start(config: HaConfig) {
        job?.cancel()
        session?.close()
        session = null
        job = scope.launch { runLoop(config) }
    }

    /** Stops the connection; cached entities are kept but become stale. */
    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        session?.close()
        session = null
        _state.value = HaConnectionState.Stopped
    }

    /** Stops and forgets all cached data (used when credentials are deleted). */
    @Synchronized
    fun reset() {
        stop()
        _entities.value = emptyMap()
        _areas.value = emptyMap()
        _lastSyncMs.value = null
        _state.value = HaConnectionState.NotConfigured
    }

    /** Skip the current backoff wait and try to connect immediately. */
    fun retryNow() {
        wake.trySend(Unit)
    }

    /**
     * Calls a service over the live session. Never queued: throws [HaException.NotConnected]
     * immediately when offline. Allowlist checks are the caller's job (see ServiceCallGuard).
     */
    override suspend fun callService(domain: String, service: String, entityId: String?, data: JsonObject): JsonElement? {
        require(HaEntity.SLUG.matches(domain) && HaEntity.SLUG.matches(service)) { "invalid domain/service" }
        val s = session ?: throw HaException.NotConnected()
        if (_state.value !is HaConnectionState.Connected) throw HaException.NotConnected()
        val fields = mutableMapOf<String, JsonElement>(
            "domain" to JsonPrimitive(domain),
            "service" to JsonPrimitive(service),
            "service_data" to data,
        )
        if (entityId != null) {
            require(HaEntity.ENTITY_ID.matches(entityId)) { "invalid entity_id" }
            fields["target"] = JsonObject(mapOf("entity_id" to JsonPrimitive(entityId)))
        }
        return s.command("call_service", fields)
    }

    private suspend fun runLoop(config: HaConfig) {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            _state.value = HaConnectionState.Connecting(attempt + 1)
            val s = try {
                connector.connect(config)
            } catch (e: HaException) {
                if (e.isCredentialProblem) {
                    _state.value = HaConnectionState.CredentialsRejected(e.userMessage)
                    return
                }
                attempt++
                waitBeforeRetry(e.userMessage, attempt)
                continue
            }
            session = s
            val reason: HaException = try {
                synchronise(s)
                attempt = 0
                _state.value = HaConnectionState.Connected(s.haVersion, clock())
                s.closed.await()
            } catch (e: HaException) {
                s.close()
                e
            }
            session = null
            if (reason.isCredentialProblem) {
                _state.value = HaConnectionState.CredentialsRejected(reason.userMessage)
                return
            }
            attempt++
            waitBeforeRetry(reason.userMessage, attempt)
        }
    }

    private suspend fun synchronise(s: HaWsSession) {
        s.subscribeEvents("state_changed") { ev -> onStateChanged(ev) }
        val states = s.command("get_states") as? JsonArray ?: throw HaException.Protocol("get_states did not return an array")
        _entities.value = states.mapNotNull { (it as? JsonObject)?.let(HaEntity::fromJson) }.associateBy { it.entityId }
        _lastSyncMs.value = clock()
        _areas.value = runCatching { loadAreas(s) }.getOrDefault(_areas.value)
    }

    private fun onStateChanged(event: JsonObject) {
        val data = event["data"] as? JsonObject ?: return
        val id = data.str("entity_id") ?: return
        val newState = (data["new_state"] as? JsonObject)?.let(HaEntity::fromJson)
        _entities.update { if (newState == null) it - id else it + (id to newState) }
        _lastSyncMs.value = clock()
        if (id == notificationEntityId && newState != null) {
            val old = (data["old_state"] as? JsonObject)?.let(HaEntity::fromJson)
            if (old?.state != newState.state) {
                HaNotification.fromEntity(newState, clock())?.let { _notifications.tryEmit(it) }
            }
        }
    }

    /** Area names via the registries. May be refused for some users; failures are ignored. */
    private suspend fun loadAreas(s: HaWsSession): Map<String, String> {
        val areaNames = (s.command("config/area_registry/list") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { a -> a.str("area_id")?.let { id -> id to (a.str("name") ?: id) } }.toMap()
        val deviceArea = runCatching { s.command("config/device_registry/list") as? JsonArray }.getOrNull().orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { d -> val id = d.str("id"); val area = d.str("area_id"); if (id != null && area != null) id to area else null }.toMap()
        return (s.command("config/entity_registry/list") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { e ->
                val id = e.str("entity_id") ?: return@mapNotNull null
                val areaId = e.str("area_id") ?: e.str("device_id")?.let(deviceArea::get) ?: return@mapNotNull null
                areaNames[areaId]?.let { id to it }
            }.toMap()
    }

    private suspend fun waitBeforeRetry(reason: String, attempt: Int) {
        val delayMs = backoff.delayFor(attempt)
        _state.value = HaConnectionState.Retrying(reason, attempt, clock() + delayMs)
        while (wake.tryReceive().isSuccess) Unit // drop stale wake-ups
        withTimeoutOrNull(delayMs) { wake.receive() }
    }
}
