package tw.rokidha.domain.glasses

import kotlinx.coroutines.flow.SharedFlow

/**
 * One user input originating from (or simulating) the glasses. Protocol-neutral: the Rokid
 * adapter translates SDK callbacks into these; the interaction layer never sees SDK types.
 */
data class GlassesInput(
    val kind: Kind,
    val source: Source,
    /** Phone clock when the event was received; used to drop stale events. */
    val receivedAtMs: Long,
    /** Monotonic per source; identical sequence = duplicate delivery of the same event. */
    val sequence: Long,
) {
    enum class Kind {
        /** The only input a single-button source can produce (e.g. the AI key via CXR-L). */
        PRIMARY,
        NEXT,
        PREVIOUS,
        SELECT,
        BACK,
    }

    enum class Source {
        /** CXR-L `onGlassAiAssistStart` / `onGlassAiInterrupt(true)`: AI key / wake. EXPERIMENTAL for control. */
        CXRL_AI_KEY,
        /** Buttons on the phone screen standing in for glasses input. SIMULATED. */
        PHONE_SIMULATOR,
        /** Future: CXR-S glasses CustomApp forwarding button/touchpad broadcasts. Not implemented. */
        CXRS_CUSTOM_APP,
    }
}

/** Implemented by adapters that can deliver glasses input to the phone. */
interface GlassesInputSource {
    val inputs: SharedFlow<GlassesInput>
}
