package tw.rokidha.domain.glasses

import kotlinx.coroutines.flow.StateFlow

/**
 * Port implemented by the Rokid adapter (module `:rokid`).
 *
 * All methods return whether the request was *accepted for sending*. The real outcome is
 * reported asynchronously through [state] (e.g. [GlassesLinkState.displayOpen]), matching the
 * SDK's callback model. Callers must never assume success from a `true` return value.
 */
interface GlassesDisplayPort {
    val state: StateFlow<GlassesLinkState>

    /** Open [screen] on the glasses. Requires [GlassesLinkState.linkReady]. */
    fun show(screen: DisplayScreen): Boolean

    /** Incrementally change the text of already shown lines (line id -> new text). */
    fun updateText(changes: Map<String, String>): Boolean

    /** Close the display surface. */
    fun hide(): Boolean
}
