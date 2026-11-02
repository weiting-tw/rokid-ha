package tw.rokidha.presentation.interaction

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import tw.rokidha.homeassistant.HaException
import tw.rokidha.homeassistant.connection.HaConnectionState
import tw.rokidha.homeassistant.connection.ServiceCaller
import tw.rokidha.homeassistant.model.HaEntity
import tw.rokidha.homeassistant.selection.QuickAction
import tw.rokidha.homeassistant.selection.ServiceCallGuard
import java.util.concurrent.ConcurrentHashMap

/**
 * The single path for every Home Assistant command, whatever triggered it (glasses input,
 * phone button, future AI intents):
 *
 *   allowlist check → HA connected? → entity present & available? → not already in flight →
 *   call_service → wait for confirming state_changed (or HA result for stateless targets).
 *
 * Nothing is queued: if any check fails the command is dropped and reported.
 */
class ActionDispatcher(
    private val guard: ServiceCallGuard,
    private val caller: ServiceCaller,
    private val entities: StateFlow<Map<String, HaEntity>>,
    private val haState: StateFlow<HaConnectionState>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val confirmTimeoutMs: Long = 10_000,
) {
    private val _status = MutableStateFlow<CommandStatus?>(null)
    /** Most recent command and its progress. */
    val status: StateFlow<CommandStatus?> = _status.asStateFlow()

    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** Reports a command blocked before reaching the dispatcher (e.g. stale or duplicate input). */
    fun reject(action: QuickAction, reason: String): CommandStatus = publish(CommandStatus.Rejected(action, clock(), reason))

    suspend fun dispatch(requested: QuickAction): CommandStatus {
        val action = try {
            guard.check(requested.entityId, requested.service)
        } catch (e: HaException.NotAllowed) {
            return reject(requested, e.userMessage)
        }
        if (haState.value !is HaConnectionState.Connected) {
            return publish(CommandStatus.Failed(action, clock(), "Home Assistant offline — command not sent"))
        }
        val before = entities.value[action.entityId]
            ?: return publish(CommandStatus.Unavailable(action, clock(), "${action.entityId} not found in Home Assistant"))
        if (before.isUnavailable) {
            return publish(CommandStatus.Unavailable(action, clock(), "${before.friendlyName} is unavailable"))
        }
        if (!inFlight.add(action.entityId)) {
            return reject(action, "A command for ${before.friendlyName} is already in progress")
        }
        try {
            publish(CommandStatus.Sending(action, clock()))
            try {
                caller.callService(action.domain, action.service, action.entityId)
            } catch (e: HaException) {
                return publish(CommandStatus.Failed(action, clock(), e.userMessage))
            }
            val expected = ExpectedState.predicate(action, before)
                ?: return publish(CommandStatus.Accepted(action, clock()))
            publish(CommandStatus.AwaitingState(action, clock()))
            val outcome = withTimeoutOrNull(confirmTimeoutMs) {
                combine(entities, haState) { map, s -> map[action.entityId] to s }
                    .first { (e, s) -> s !is HaConnectionState.Connected || (e != null && expected(e)) }
            }
            return when {
                outcome == null -> publish(CommandStatus.Unconfirmed(action, clock(), entities.value[action.entityId]?.state))
                outcome.second !is HaConnectionState.Connected ->
                    publish(CommandStatus.Failed(action, clock(), "Connection lost — result unknown, not retried"))
                else -> publish(CommandStatus.Confirmed(action, clock(), outcome.first!!.state))
            }
        } finally {
            inFlight.remove(action.entityId)
        }
    }

    private fun publish(s: CommandStatus): CommandStatus {
        _status.value = s
        return s
    }
}
