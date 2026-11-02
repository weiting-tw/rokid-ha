package tw.rokidha.presentation.interaction

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tw.rokidha.domain.glasses.GlassesInput
import tw.rokidha.domain.glasses.GlassesLinkState
import tw.rokidha.homeassistant.connection.HaConnectionState
import tw.rokidha.homeassistant.selection.GlassesSelection
import tw.rokidha.homeassistant.selection.QuickAction

/** What the glasses should show about interaction; rendered by DashboardRenderer. */
data class InteractionView(
    /** Quick action under the cursor (the "target" of the next confirm). */
    val target: QuickAction?,
    /** Armed = waiting for the confirming second input until [armedUntilMs]. */
    val armedUntilMs: Long?,
    val status: CommandStatus?,
    /** Short hint, e.g. why the last input was ignored. */
    val hint: String?,
)

/**
 * Turns glasses inputs into commands with a two-step confirmation:
 *
 *   PRIMARY / SELECT  → arm the target ("press again to confirm", [armTimeoutMs])
 *   PRIMARY / SELECT while armed (same target, in time) → dispatch
 *   NEXT / PREVIOUS   → move the cursor over the configured quick actions (disarms)
 *   BACK, timeout, HA offline, glasses link lost → disarm
 *
 * Two steps are mandatory for glasses input because the only CXR-L input (AI key / wake) may
 * also fire on the wake word or an accidental touch. The armed state is cleared whenever HA or
 * the glasses disconnect, so a press from before a reconnect can never complete a command.
 */
class GlassesInteractionController(
    private val selection: StateFlow<GlassesSelection>,
    private val haState: StateFlow<HaConnectionState>,
    private val glassesLink: StateFlow<GlassesLinkState>,
    private val dispatcher: ActionDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val armTimeoutMs: Long = 5_000,
    private val gate: InputGate = InputGate(clock),
) {
    /** Off by default: glasses input is ignored until the user enables it on the phone. */
    val enabled = MutableStateFlow(false)

    private val cursorId = MutableStateFlow<String?>(null)
    private val armed = MutableStateFlow<Pair<String, Long>?>(null) // action id, deadline
    private val hint = MutableStateFlow<String?>(null)

    private val _view = MutableStateFlow<InteractionView?>(null)
    val view: StateFlow<InteractionView?> = _view.asStateFlow()

    private var scope: CoroutineScope? = null
    private var timeoutJob: Job? = null

    fun start(scope: CoroutineScope): Job {
        this.scope = scope
        return scope.launch {
            launch {
                combine(haState, glassesLink) { h, g -> h is HaConnectionState.Connected && g.linkReady }
                    .collect { ok -> if (!ok) disarm("connection changed") }
            }
            combine(enabled, selection, cursorId, armed, combine(hint, dispatcher.status) { h, s -> h to s }) { en, sel, cur, arm, hs ->
                if (!en) return@combine null
                val target = currentTarget(sel, cur)
                InteractionView(
                    target = target,
                    armedUntilMs = arm?.takeIf { it.first == target?.id }?.second,
                    status = hs.second?.takeIf { s -> sel.quickActions.any { it.id == s.action.id } },
                    hint = hs.first,
                )
            }.collect { _view.value = it }
        }
    }

    /** Phone UI: choose which quick action the glasses control. */
    fun selectTarget(actionId: String) {
        cursorId.value = actionId
        disarm(null)
    }

    fun onInput(e: GlassesInput) {
        if (!enabled.value) return
        when (val v = gate.check(e)) {
            is InputGate.Verdict.Drop -> { hint.value = "Ignored: ${v.reason}"; return }
            InputGate.Verdict.Accept -> hint.value = null
        }
        val actions = selection.value.quickActions
        if (actions.isEmpty()) {
            hint.value = "No quick actions configured on the phone"
            return
        }
        when (e.kind) {
            GlassesInput.Kind.NEXT -> moveCursor(+1)
            GlassesInput.Kind.PREVIOUS -> moveCursor(-1)
            GlassesInput.Kind.BACK -> disarm("Cancelled")
            GlassesInput.Kind.PRIMARY, GlassesInput.Kind.SELECT -> confirmOrArm()
        }
    }

    private fun currentTarget(sel: GlassesSelection, cur: String?): QuickAction? =
        sel.quickActions.firstOrNull { it.id == cur } ?: sel.quickActions.firstOrNull()

    private fun moveCursor(delta: Int) {
        val actions = selection.value.quickActions
        val i = actions.indexOfFirst { it.id == currentTarget(selection.value, cursorId.value)?.id }.coerceAtLeast(0)
        cursorId.value = actions[(i + delta).mod(actions.size)].id
        disarm(null)
    }

    private fun confirmOrArm() {
        val target = currentTarget(selection.value, cursorId.value) ?: return
        val now = clock()
        val a = armed.value
        if (a != null && a.first == target.id && now <= a.second) {
            armed.value = null
            timeoutJob?.cancel()
            if (haState.value !is HaConnectionState.Connected) {
                dispatcher.reject(target, "Home Assistant offline — not sent")
                return
            }
            scope?.launch { dispatcher.dispatch(target) }
            return
        }
        val deadline = now + armTimeoutMs
        armed.value = target.id to deadline
        timeoutJob?.cancel()
        timeoutJob = scope?.launch {
            delay(armTimeoutMs)
            if (armed.value?.second == deadline) disarm("Timed out — nothing sent")
        }
    }

    private fun disarm(reason: String?) {
        if (armed.value != null) {
            armed.value = null
            if (reason != null) hint.value = reason
        }
        timeoutJob?.cancel()
    }
}
