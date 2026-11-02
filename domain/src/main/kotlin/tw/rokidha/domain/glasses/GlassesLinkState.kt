package tw.rokidha.domain.glasses

/**
 * Protocol-neutral view of the phone ⇄ glasses link.
 *
 * Mirrors the stages documented for Rokid CXR-L (see docs/SDK_RESEARCH.md §5) without
 * exposing any Rokid SDK type, so Home Assistant / presentation code can depend on it.
 */
data class GlassesLinkState(
    val stage: Stage = Stage.IDLE,
    /** Companion service (Rokid AI App) bound: `onCXRLConnected(true)`. */
    val transportConnected: Boolean = false,
    /** Glasses Bluetooth connected: `onGlassBtConnected(true)`. */
    val glassesConnected: Boolean = false,
    /** Display surface currently open on the glasses: `onCustomViewOpened`. */
    val displayOpen: Boolean = false,
    /** Last error reported by the adapter, human readable, never contains credentials. */
    val lastError: String? = null,
) {
    /** "Link ready" per official docs: transport AND glasses Bluetooth both up. */
    val linkReady: Boolean get() = transportConnected && glassesConnected

    enum class Stage {
        IDLE,
        AUTHORIZING,
        AUTHORIZED,
        CONNECTING,
        LINK_READY,
        DISPLAY_OPEN,
        DISCONNECTED,
        ERROR,
    }
}
