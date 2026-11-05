package tw.rokidha.glasses

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.os.Build
import tw.rokidha.glassesproto.GlassesKeyMap
import tw.rokidha.glassesproto.GlassesProtocol

/**
 * Glasses UI: renders the lines the phone sends and reports input.
 *
 * Shows a clear "PHONE DISCONNECTED" state instead of keeping old values on screen, and "Waiting
 * for phone…" until the first frame arrives. Uses plain views, black background (transparent on
 * the waveguide) and green text (the display is monochrome green).
 */
class MainActivity : Activity() {
    private lateinit var lines: LinearLayout
    private lateinit var footer: TextView
    private lateinit var link: PhoneLink
    @Volatile private var connected = false
    private var lastFrame: GlassesProtocol.UiFrame? = null
    private var lastInput = "-"

    private val keyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            report(GlassesKeyMap.kindForBroadcast(action), GlassesKeyMap.shortName(action))
            // Consume our inputs (official sample does the same); leave system exit / AI key alone.
            if (isOrderedBroadcast && GlassesKeyMap.consume(action)) abortBroadcast()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }
        lines = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        footer = text("", 13f, DIM)
        root.addView(lines)
        root.addView(footer)
        setContentView(root)
        render()

        link = PhoneLink(
            onFrame = { f -> runOnUiThread { lastFrame = f; render() } },
            onConnection = { c -> runOnUiThread { connected = c; render() } },
        )
        link.start()
        val filter = IntentFilter().apply { GlassesKeyMap.ACTIONS.forEach(::addAction) }
        // Broadcasts come from the glasses system (another app), so the receiver must be exported.
        // The flag exists from Android 13; the glasses run Android 12, where it is not needed.
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(keyReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(keyReceiver, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        link.sendHello() // ask the phone for the current frame
    }

    override fun onDestroy() {
        unregisterReceiver(keyReceiver)
        super.onDestroy()
    }

    /** KeyEvents (if the hardware delivers any) are mapped too and always reported for diagnostics. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount > 0) return true
        val kind = GlassesKeyMap.kindForKeyCode(keyCode)
        report(kind, "KEY_${KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")}")
        return if (kind != null) true else super.onKeyDown(keyCode, event)
    }

    private fun report(kind: tw.rokidha.domain.glasses.GlassesInput.Kind?, raw: String) {
        lastInput = "$raw→${kind ?: "-"}"
        link.sendInput(kind, raw)
        render()
    }

    private fun render() {
        lines.removeAllViews()
        val f = lastFrame
        when {
            !connected && f == null -> lines.addView(text("Waiting for phone…", 20f, GREEN))
            !connected -> {
                lines.addView(text("PHONE DISCONNECTED", 22f, GREEN, bold = true))
                lines.addView(text("Data not live. Check the phone app.", 16f, DIM))
            }
            f == null -> lines.addView(text("Connected · waiting for data…", 18f, GREEN))
            else -> f.lines.forEach { l ->
                when (l.style) {
                    "TITLE" -> lines.addView(text(l.text, 22f, GREEN, bold = true))
                    "CAPTION" -> lines.addView(text(l.text, 14f, DIM))
                    else -> lines.addView(text(l.text, 18f, GREEN))
                }
            }
        }
        footer.text = "input: $lastInput"
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        val GREEN = Color.rgb(0, 255, 0)
        val DIM = Color.rgb(0, 160, 0)
    }
}
