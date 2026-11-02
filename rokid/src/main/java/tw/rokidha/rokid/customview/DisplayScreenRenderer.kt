package tw.rokidha.rokid.customview

import tw.rokidha.domain.glasses.DisplayLine
import tw.rokidha.domain.glasses.DisplayScreen

/**
 * Maps the protocol-neutral [DisplayScreen] onto a CustomView tree.
 *
 * Layout values (margins, sizes, colours) are taken from the official v1.0.4 example and are
 * NOT yet tuned on the target device; see docs/DEVICE_VERIFICATION_CHECKLIST.md (V-11).
 * The display is monochrome green; the official docs say colours are mapped to green, so
 * we only vary brightness.
 */
class DisplayScreenRenderer(
    private val style: Style = Style(),
) {
    data class Style(
        val rootMarginTop: String = "160dp",
        val rootMarginBottom: String = "80dp",
        val rootPaddingHorizontal: String = "16dp",
        val background: String = "#FF000000",
        val titleColor: String = "#FF00FF00",
        val bodyColor: String = "#FF00FF00",
        val captionColor: String = "#FF00A000",
        val titleSize: String = "22sp",
        val bodySize: String = "18sp",
        val captionSize: String = "14sp",
        val lineSpacing: String = "4dp",
    )

    fun render(screen: DisplayScreen): CustomViewNode = CustomViews.linearLayout(
        id = screen.id,
        orientation = "vertical",
        gravity = "center_horizontal",
        backgroundColor = style.background,
        box = CustomViews.Box(
            marginTop = style.rootMarginTop,
            marginBottom = style.rootMarginBottom,
            paddingStart = style.rootPaddingHorizontal,
            paddingEnd = style.rootPaddingHorizontal,
        ),
        children = screen.lines.map(::renderLine),
    )

    private fun renderLine(line: DisplayLine): CustomViewNode {
        val (color, size, textStyle) = when (line.style) {
            DisplayLine.Style.TITLE -> Triple(style.titleColor, style.titleSize, "bold")
            DisplayLine.Style.BODY -> Triple(style.bodyColor, style.bodySize, null)
            DisplayLine.Style.CAPTION -> Triple(style.captionColor, style.captionSize, null)
        }
        return CustomViews.textView(
            id = line.id,
            text = line.text,
            textColor = color,
            textSize = size,
            textStyle = textStyle,
            gravity = "start",
            box = CustomViews.Box(paddingTop = style.lineSpacing),
        )
    }

    /** Text-only incremental update for already rendered lines. */
    fun renderTextUpdates(changes: Map<String, String>): List<CustomViewUpdate> =
        changes.map { (id, text) -> CustomViewUpdate(id, mapOf("text" to text)) }
}
