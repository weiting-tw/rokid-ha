package tw.rokidha.rokid.customview

/**
 * One node of a Rokid CXR-L CustomView tree.
 *
 * Shape follows the official schema (CXR-L docs v1.0.4, "眼镜端自定义View" chapter):
 * `{"type": String, "props": {String: String}, "children": [node...]}`.
 * Every prop value is a string in the protocol (including booleans and dimensions).
 *
 * Use the factory functions in [CustomViews] instead of the constructor, and run
 * [CustomViewValidator] before sending anything to the glasses.
 */
data class CustomViewNode(
    val type: String,
    val props: Map<String, String>,
    val children: List<CustomViewNode> = emptyList(),
) {
    val id: String? get() = props["id"]

    /** Depth-first list of this node and all descendants. */
    fun flatten(): List<CustomViewNode> = listOf(this) + children.flatMap { it.flatten() }

    fun toJson(): String = buildString { writeTo(this) }

    internal fun writeTo(sb: StringBuilder) {
        sb.append("{\"type\":").append(Json.quote(type))
        sb.append(",\"props\":")
        Json.writeStringMap(sb, props)
        if (children.isNotEmpty()) {
            sb.append(",\"children\":[")
            children.forEachIndexed { i, child ->
                if (i > 0) sb.append(',')
                child.writeTo(sb)
            }
            sb.append(']')
        }
        sb.append('}')
    }

    object Types {
        const val LINEAR_LAYOUT = "LinearLayout"
        const val RELATIVE_LAYOUT = "RelativeLayout"
        const val TEXT_VIEW = "TextView"
        const val IMAGE_VIEW = "ImageView"
        val ALL = setOf(LINEAR_LAYOUT, RELATIVE_LAYOUT, TEXT_VIEW, IMAGE_VIEW)
        val CONTAINERS = setOf(LINEAR_LAYOUT, RELATIVE_LAYOUT)
    }
}

/** One entry of a `customViewUpdate` array: `{"action":"update","id":..,"props":{..}}`. */
data class CustomViewUpdate(
    val id: String,
    val props: Map<String, String>,
) {
    val action: String get() = ACTION_UPDATE

    companion object {
        /** The only action documented by the official schema. */
        const val ACTION_UPDATE = "update"

        fun toJson(updates: List<CustomViewUpdate>): String = buildString {
            append('[')
            updates.forEachIndexed { i, u ->
                if (i > 0) append(',')
                append("{\"action\":").append(Json.quote(u.action))
                append(",\"id\":").append(Json.quote(u.id))
                append(",\"props\":")
                Json.writeStringMap(this, u.props)
                append('}')
            }
            append(']')
        }
    }
}

/** Icon entry for `customViewSetIcons`: `[{"name":..,"data":"<base64 PNG>"}]`. */
data class CustomViewIcon(val name: String, val base64Png: String) {
    companion object {
        fun toJson(icons: List<CustomViewIcon>): String = buildString {
            append('[')
            icons.forEachIndexed { i, icon ->
                if (i > 0) append(',')
                append("{\"name\":").append(Json.quote(icon.name))
                append(",\"data\":").append(Json.quote(icon.base64Png))
                append('}')
            }
            append(']')
        }
    }
}

/** Minimal JSON string writer (no external dependency; org.json is stubbed in unit tests). */
internal object Json {
    fun quote(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
            }
        }
        append('"')
    }

    fun writeStringMap(sb: StringBuilder, map: Map<String, String>) {
        sb.append('{')
        map.entries.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append(',')
            sb.append(quote(k)).append(':').append(quote(v))
        }
        sb.append('}')
    }
}
