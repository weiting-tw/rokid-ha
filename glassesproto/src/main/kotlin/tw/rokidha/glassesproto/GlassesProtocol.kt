package tw.rokidha.glassesproto

import tw.rokidha.domain.glasses.GlassesInput

/**
 * Wire format between the phone app (CXR-L, CUSTOMAPP session) and the glasses app (CXR-S).
 *
 * Transport (official CXR-L "自定义指令" chapter):
 *   phone → glasses : CXRLink.sendCustomCmd([CH_TO_GLASSES], Caps)  ↔  CXRServiceBridge.subscribe
 *   glasses → phone : CXRServiceBridge.sendMessage([CH_TO_PHONE], Caps) ↔ ICustomCmdCbk.onCustomCmdResult
 *
 * A message is an ordered list of String / Long fields (written to Caps as string / int64):
 *   ["ui", version, rev, lineCount, (id, text, style) × lineCount, statusText]
 *   ["in", version, session, seq, kind, raw]
 *   ["hello", version, session]
 *
 * Security: the glasses only receive display text, and only send a semantic input kind
 * (NEXT / PREVIOUS / SELECT / BACK) plus the raw event name for diagnostics. They never send an
 * entity id or service name, so a compromised or buggy glasses app cannot choose what to call.
 */
object GlassesProtocol {
    const val VERSION = 1L

    /** Phone → glasses channel (glasses subscribe to it). Not one of the SDK's reserved names. */
    const val CH_TO_GLASSES = "rokidha_ui"

    /** Glasses → phone channel. */
    const val CH_TO_PHONE = "rokidha_evt"

    const val GLASSES_PACKAGE = "tw.rokidha.glasses"
    const val GLASSES_ENTRY = "tw.rokidha.glasses.MainActivity"

    const val MAX_LINES = 16
    const val MAX_TEXT = 160
    private val ID = Regex("[a-zA-Z0-9_]{1,32}")

    data class Line(val id: String, val text: String, val style: String)

    sealed interface Message

    /** Full screen content. [rev] increases with every frame so the glasses can drop old ones. */
    data class UiFrame(val rev: Long, val lines: List<Line>, val status: String) : Message

    /** One input on the glasses. [session] changes whenever the glasses app process restarts. */
    data class InputEvent(val session: String, val seq: Long, val kind: GlassesInput.Kind?, val raw: String) : Message

    /** Sent by the glasses app when its bridge is (re)connected; the phone answers with a UiFrame. */
    data class Hello(val session: String) : Message

    /** Kinds the glasses may send. PRIMARY is reserved for single-button sources. */
    val ALLOWED_KINDS = setOf(GlassesInput.Kind.NEXT, GlassesInput.Kind.PREVIOUS, GlassesInput.Kind.SELECT, GlassesInput.Kind.BACK)

    fun encode(m: Message): List<Any> = when (m) {
        is UiFrame -> buildList {
            add("ui"); add(VERSION); add(m.rev)
            val lines = m.lines.take(MAX_LINES)
            add(lines.size.toLong())
            lines.forEach { add(it.id); add(it.text.take(MAX_TEXT)); add(it.style) }
            add(m.status.take(MAX_TEXT))
        }
        is InputEvent -> listOf("in", VERSION, m.session, m.seq, m.kind?.name ?: "", m.raw.take(64))
        is Hello -> listOf("hello", VERSION, m.session)
    }

    /** Strict decoding; returns null for anything malformed, oversized or from another version. */
    fun decode(f: List<Any?>): Message? = runCatching {
        val type = f[0] as String
        if (f[1] as Long != VERSION) return null
        when (type) {
            "ui" -> {
                val rev = f[2] as Long
                val n = (f[3] as Long).toInt()
                if (n !in 0..MAX_LINES || f.size != 5 + n * 3) return null
                val lines = (0 until n).map { i ->
                    val id = f[4 + i * 3] as String
                    if (!ID.matches(id)) return null
                    Line(id, (f[5 + i * 3] as String).take(MAX_TEXT), f[6 + i * 3] as String)
                }
                UiFrame(rev, lines, (f[4 + n * 3] as String).take(MAX_TEXT))
            }
            "in" -> {
                if (f.size != 6) return null
                val session = f[2] as String
                if (!ID.matches(session)) return null
                val kindName = f[4] as String
                // Empty kind = diagnostic report of an unmapped hardware event (never executes anything).
                val kind = if (kindName.isEmpty()) {
                    null
                } else {
                    GlassesInput.Kind.entries.firstOrNull { it.name == kindName }?.takeIf { it in ALLOWED_KINDS } ?: return null
                }
                InputEvent(session, f[3] as Long, kind, (f[5] as String).take(64))
            }
            "hello" -> {
                if (f.size != 3) return null
                val session = f[2] as String
                if (!ID.matches(session)) null else Hello(session)
            }
            else -> null
        }
    }.getOrNull()
}
