package tw.rokidha.glassesproto

import tw.rokidha.domain.glasses.GlassesInput.Kind

/**
 * Glasses-side input sources (official CXR-L doc "眼镜端按键与系统广播" / CXR-S "按键") and the
 * semantic input each one produces. Unmapped events are still reported (kind = null) so the
 * phone can show what the hardware actually delivered during verification.
 */
object GlassesKeyMap {
    const val CLICK = "com.android.action.ACTION_SPRITE_BUTTON_CLICK"
    const val BUTTON_DOWN = "com.android.action.ACTION_SPRITE_BUTTON_DOWN"
    const val BUTTON_UP = "com.android.action.ACTION_SPRITE_BUTTON_UP"
    const val DOUBLE_CLICK = "com.android.action.ACTION_SPRITE_BUTTON_DOUBLE_CLICK"
    const val LONG_PRESS = "com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS"
    const val AI_START = "com.android.action.ACTION_AI_START"
    const val TWO_FINGER_SINGLE_TAP = "com.android.action.ACTION_TWO_FINGER_SINGLE_TAP"
    const val TWO_FINGER_DOUBLE_TAP = "com.android.action.ACTION_TWO_FINGER_DOUBLE_TAP"
    const val TWO_FINGER_SWIPE_FORWARD = "com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD"
    const val TWO_FINGER_SWIPE_BACK = "com.android.action.ACTION_TWO_FINGER_SWIPE_BACK"
    const val SETTINGS_KEY = "com.android.action.ACTION_SETTINGS_KEY"

    /** Every documented broadcast action, registered by the glasses app. */
    val ACTIONS = listOf(
        CLICK, BUTTON_DOWN, BUTTON_UP, DOUBLE_CLICK, LONG_PRESS, AI_START,
        TWO_FINGER_SINGLE_TAP, TWO_FINGER_DOUBLE_TAP, TWO_FINGER_SWIPE_FORWARD, TWO_FINGER_SWIPE_BACK, SETTINGS_KEY,
    )

    fun kindForBroadcast(action: String): Kind? = when (action) {
        CLICK, TWO_FINGER_SINGLE_TAP -> Kind.SELECT
        TWO_FINGER_SWIPE_FORWARD -> Kind.NEXT
        TWO_FINGER_SWIPE_BACK -> Kind.PREVIOUS
        LONG_PRESS -> Kind.BACK
        else -> null // down/up (part of a click), double click (system exit), AI key, settings key
    }

    /**
     * Whether the glasses app consumes the broadcast (abortBroadcast). Double click is the system
     * "exit" and the AI key belongs to the Rokid assistant: both are left to the system.
     */
    fun consume(action: String): Boolean = action !in setOf(DOUBLE_CLICK, AI_START, SETTINGS_KEY)

    /** Android KeyEvent codes that may arrive in the Activity (UNKNOWN on this hardware). */
    fun kindForKeyCode(keyCode: Int): Kind? = when (keyCode) {
        22, 20 -> Kind.NEXT // DPAD_RIGHT, DPAD_DOWN
        21, 19 -> Kind.PREVIOUS // DPAD_LEFT, DPAD_UP
        23, 66 -> Kind.SELECT // DPAD_CENTER, ENTER
        else -> null
    }

    /** Short name for diagnostics, e.g. "SPRITE_BUTTON_CLICK". */
    fun shortName(action: String): String = action.substringAfterLast(".ACTION_")
}
