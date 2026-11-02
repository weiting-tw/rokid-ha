package tw.rokidha.rokid.customview

import tw.rokidha.rokid.customview.CustomViewNode.Types

/**
 * Typed factories for the four node types in the official CustomView schema.
 * Optional arguments left `null` are omitted from the JSON.
 */
object CustomViews {

    fun linearLayout(
        id: String,
        orientation: String = "vertical",
        width: String = "match_parent",
        height: String = "match_parent",
        gravity: String? = null,
        layoutGravity: String? = null,
        backgroundColor: String? = null,
        box: Box = Box(),
        children: List<CustomViewNode> = emptyList(),
    ): CustomViewNode {
        val props = linkedMapOf(
            "id" to id,
            "layout_width" to width,
            "layout_height" to height,
            "orientation" to orientation,
        )
        props.putOpt("gravity", gravity)
        props.putOpt("layout_gravity", layoutGravity)
        props.putOpt("backgroundColor", backgroundColor)
        // Official docs: LinearLayout serialises paddingStart/End as paddingLeft/paddingRight.
        box.writeTo(props, horizontalPaddingKeys = "paddingLeft" to "paddingRight")
        return CustomViewNode(Types.LINEAR_LAYOUT, props, children)
    }

    fun textView(
        id: String,
        text: String,
        width: String = "match_parent",
        height: String = "wrap_content",
        textColor: String? = null,
        textSize: String? = null,
        gravity: String? = null,
        textStyle: String? = null,
        box: Box = Box(),
    ): CustomViewNode {
        val props = linkedMapOf(
            "id" to id,
            "layout_width" to width,
            "layout_height" to height,
            "text" to text,
        )
        props.putOpt("textColor", textColor)
        props.putOpt("textSize", textSize)
        props.putOpt("gravity", gravity)
        props.putOpt("textStyle", textStyle)
        box.writeTo(props, horizontalPaddingKeys = "paddingStart" to "paddingEnd")
        return CustomViewNode(Types.TEXT_VIEW, props)
    }

    fun imageView(
        id: String,
        iconName: String,
        width: String,
        height: String,
        scaleType: String? = null,
        box: Box = Box(),
    ): CustomViewNode {
        val props = linkedMapOf(
            "id" to id,
            "layout_width" to width,
            "layout_height" to height,
            "name" to iconName,
        )
        props.putOpt("scaleType", scaleType)
        box.writeTo(props, horizontalPaddingKeys = "paddingStart" to "paddingEnd")
        return CustomViewNode(Types.IMAGE_VIEW, props)
    }

    /** Margins and paddings, each a `{N}dp` string or null. */
    data class Box(
        val marginStart: String? = null,
        val marginEnd: String? = null,
        val marginTop: String? = null,
        val marginBottom: String? = null,
        val paddingStart: String? = null,
        val paddingEnd: String? = null,
        val paddingTop: String? = null,
        val paddingBottom: String? = null,
    ) {
        internal fun writeTo(props: MutableMap<String, String>, horizontalPaddingKeys: Pair<String, String>) {
            props.putOpt("marginStart", marginStart)
            props.putOpt("marginEnd", marginEnd)
            props.putOpt("marginTop", marginTop)
            props.putOpt("marginBottom", marginBottom)
            props.putOpt(horizontalPaddingKeys.first, paddingStart)
            props.putOpt(horizontalPaddingKeys.second, paddingEnd)
            props.putOpt("paddingTop", paddingTop)
            props.putOpt("paddingBottom", paddingBottom)
        }
    }

    private fun MutableMap<String, String>.putOpt(key: String, value: String?) {
        if (value != null) put(key, value)
    }
}
