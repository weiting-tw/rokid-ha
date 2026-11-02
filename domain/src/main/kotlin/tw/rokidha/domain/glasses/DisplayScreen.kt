package tw.rokidha.domain.glasses

/**
 * Minimal, protocol-neutral description of what to show on the glasses.
 *
 * Deliberately limited to id-addressable text lines: this is what the CustomView schema
 * verified so far supports. Richer layouts are added only after they are verified on the
 * target device (docs/SDK_COMPATIBILITY.md).
 */
data class DisplayScreen(
    val id: String,
    val lines: List<DisplayLine>,
) {
    init {
        require(ID_PATTERN.matches(id)) { "screen id must match ${ID_PATTERN.pattern}: '$id'" }
        val ids = lines.map { it.id }
        require(ids.size == ids.toSet().size) { "line ids must be unique within a screen: $ids" }
    }

    companion object {
        /** Same constraint as CustomView `props.id` ([a-zA-Z0-9_]+). */
        val ID_PATTERN = Regex("[a-zA-Z0-9_]+")
    }
}

data class DisplayLine(
    val id: String,
    val text: String,
    val style: Style = Style.BODY,
) {
    init {
        require(DisplayScreen.ID_PATTERN.matches(id)) { "line id must match [a-zA-Z0-9_]+: '$id'" }
    }

    enum class Style { TITLE, BODY, CAPTION }
}
