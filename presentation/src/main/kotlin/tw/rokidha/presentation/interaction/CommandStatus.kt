package tw.rokidha.presentation.interaction

import tw.rokidha.homeassistant.model.HaEntity
import tw.rokidha.homeassistant.selection.QuickAction

/**
 * Lifecycle of one command. Success is only reported after Home Assistant shows the new
 * state ([Confirmed]) or, for stateless targets (scripts, buttons…), after HA accepted the call
 * ([Accepted]). "Sent" is never displayed as success.
 */
sealed interface CommandStatus {
    val action: QuickAction
    val atMs: Long
    val isTerminal: Boolean get() = this !is Sending && this !is AwaitingState

    data class Sending(override val action: QuickAction, override val atMs: Long) : CommandStatus
    /** HA accepted the call; waiting for the matching `state_changed`. */
    data class AwaitingState(override val action: QuickAction, override val atMs: Long) : CommandStatus
    /** HA reported the expected new state. */
    data class Confirmed(override val action: QuickAction, override val atMs: Long, val state: String) : CommandStatus
    /** Stateless target (script/button/play-pause): HA accepted and ran the service. */
    data class Accepted(override val action: QuickAction, override val atMs: Long) : CommandStatus
    /** HA accepted the call but the expected state did not appear in time. */
    data class Unconfirmed(override val action: QuickAction, override val atMs: Long, val state: String?) : CommandStatus
    /** Blocked before sending (allowlist, invalid service, duplicate, stale input). Nothing was sent. */
    data class Rejected(override val action: QuickAction, override val atMs: Long, val reason: String) : CommandStatus
    /** Target entity missing or `unavailable` in HA. Nothing was sent. */
    data class Unavailable(override val action: QuickAction, override val atMs: Long, val reason: String) : CommandStatus
    /** Sending failed or the connection dropped (outcome unknown). Never retried automatically. */
    data class Failed(override val action: QuickAction, override val atMs: Long, val reason: String) : CommandStatus
}

/** What state proves that a service call took effect. `null` = stateless, HA's result is the proof. */
object ExpectedState {
    fun predicate(action: QuickAction, before: HaEntity?): ((HaEntity) -> Boolean)? {
        val pre = before?.state
        val changedBefore = before?.lastChanged
        return when (action.domain to action.service) {
            "lock" to "lock" -> { e -> e.state == "locked" }
            "lock" to "unlock" -> { e -> e.state == "unlocked" }
            "cover" to "open_cover" -> { e -> e.state == "open" || e.state == "opening" }
            "cover" to "close_cover" -> { e -> e.state == "closed" || e.state == "closing" }
            // A scene's state is the timestamp of its last activation.
            "scene" to "turn_on" -> { e -> e.lastChanged != changedBefore || e.state != pre }
            else -> when (action.service) {
                "turn_on" -> if (action.domain in ON_OFF) ({ e -> e.state != "off" && !e.isUnavailable }) else null
                "turn_off" -> if (action.domain in ON_OFF) ({ e -> e.state == "off" }) else null
                "toggle" -> if (action.domain in ON_OFF && (pre == "on" || pre == "off")) {
                    val target = if (pre == "on") "off" else "on"
                    ({ e -> e.state == target })
                } else null
                else -> null // script.turn_on, button.press, media_play_pause, stop_cover
            }
        }
    }

    private val ON_OFF = setOf("light", "switch", "fan", "input_boolean", "climate", "media_player")
}
