package tw.rokidha.rokid.customview

import tw.rokidha.rokid.customview.CustomViewNode.Types

/**
 * Validates CustomView trees and updates against the official schema
 * (CXR-L docs v1.0.4, "眼镜端自定义View" → "CustomView JSON Schema").
 *
 * The glasses-side renderer's behaviour on invalid input is undocumented, so we refuse to
 * send anything that does not match the documented schema. Unknown keys are rejected
 * on purpose: if a future firmware supports more, add them here only after verification.
 */
object CustomViewValidator {

    private val ID = Regex("[a-zA-Z0-9_]+")
    private val DP = Regex("\\d+(\\.\\d+)?dp")
    private val SP = Regex("\\d+(\\.\\d+)?sp")
    private val COLOR = Regex("#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})")
    private val SIZE = Regex("match_parent|wrap_content|\\d+(\\.\\d+)?dp")
    private val BOOL = setOf("true", "false")

    private val GRAVITY = setOf("center", "center_vertical", "center_horizontal", "top", "bottom", "start", "end")
    private val ORIENTATION = setOf("vertical", "horizontal")
    private val TEXT_STYLE = setOf("bold", "italic", "bold_italic")
    private val SCALE_TYPE = setOf(
        "center", "center_crop", "center_inside", "fit_center", "fit_end", "fit_start", "fit_xy", "matrix",
    )

    private val MARGINS = setOf("marginStart", "marginEnd", "marginTop", "marginBottom")
    private val V_PADDING = setOf("paddingTop", "paddingBottom")
    private val COMMON = setOf("id", "layout_width", "layout_height", "backgroundColor") + MARGINS + V_PADDING

    private val RELATIVE_ID_REFS = setOf(
        "layout_toStartOf", "layout_toEndOf", "layout_above", "layout_below",
        "layout_alignStart", "layout_alignEnd", "layout_alignTop", "layout_alignBottom", "layout_alignBaseLine",
    )

    // "aligin" spelling is the protocol key name per official docs ("协议键名保留此拼写").
    private val RELATIVE_BOOLS = setOf(
        "layout_aliginParentStart", "layout_aliginParentEnd", "layout_aliginParentTop", "layout_aliginParentBottom",
        "layout_centerInParent", "layout_centerHorizontal", "layout_centerVertical",
    )

    private val ALLOWED: Map<String, Set<String>> = mapOf(
        Types.LINEAR_LAYOUT to COMMON + setOf("orientation", "gravity", "layout_gravity", "paddingLeft", "paddingRight"),
        Types.RELATIVE_LAYOUT to COMMON + setOf("paddingStart", "paddingEnd") + RELATIVE_ID_REFS + RELATIVE_BOOLS,
        Types.TEXT_VIEW to COMMON + setOf("paddingStart", "paddingEnd", "text", "textColor", "textSize", "gravity", "textStyle"),
        Types.IMAGE_VIEW to COMMON + setOf("paddingStart", "paddingEnd", "name", "scaleType"),
    )

    /** Returns a list of human-readable problems; empty means valid. */
    fun validateTree(root: CustomViewNode, knownIcons: Set<String>? = null): List<String> {
        val errors = mutableListOf<String>()
        val nodes = root.flatten()
        val ids = nodes.mapNotNull { it.id }
        ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach {
            errors += "duplicate id '$it'"
        }
        val idSet = ids.toSet()
        nodes.forEach { validateNode(it, idSet, knownIcons, errors) }
        return errors
    }

    /** Validates an update batch against the ids of the currently open tree. */
    fun validateUpdates(updates: List<CustomViewUpdate>, openTree: CustomViewNode): List<String> {
        val errors = mutableListOf<String>()
        if (updates.isEmpty()) errors += "update list is empty"
        val byId = openTree.flatten().associateBy { it.id }
        updates.forEach { u ->
            val target = byId[u.id]
            if (target == null) {
                errors += "update targets unknown id '${u.id}'"
                return@forEach
            }
            if (u.props.isEmpty()) errors += "update for '${u.id}' has no props"
            if ("id" in u.props) errors += "update for '${u.id}' must not change id"
            val where = "${target.type}#${u.id} (update)"
            checkProps(target.type, u.props, where, openTree.flatten().mapNotNull { it.id }.toSet(), null, errors)
        }
        return errors
    }

    private fun validateNode(
        node: CustomViewNode,
        ids: Set<String>,
        knownIcons: Set<String>?,
        errors: MutableList<String>,
    ) {
        val where = "${node.type}#${node.id ?: "?"}"
        if (node.type !in Types.ALL) {
            errors += "$where: unsupported type '${node.type}'"
            return
        }
        val p = node.props
        val id = p["id"]
        if (id == null) errors += "$where: missing props.id" else if (!ID.matches(id)) errors += "$where: id must match [a-zA-Z0-9_]+"
        listOf("layout_width", "layout_height").forEach { if (it !in p) errors += "$where: missing $it" }
        when (node.type) {
            Types.LINEAR_LAYOUT -> if ("orientation" !in p) errors += "$where: missing orientation"
            Types.TEXT_VIEW -> if ("text" !in p) errors += "$where: missing text"
            Types.IMAGE_VIEW -> if ("name" !in p) errors += "$where: missing name"
        }
        if (node.children.isNotEmpty() && node.type !in Types.CONTAINERS) {
            errors += "$where: only LinearLayout/RelativeLayout may have children"
        }
        checkProps(node.type, p, where, ids, knownIcons, errors)
    }

    private fun checkProps(
        type: String,
        p: Map<String, String>,
        where: String,
        ids: Set<String>,
        knownIcons: Set<String>?,
        errors: MutableList<String>,
    ) {
        val allowed = ALLOWED.getValue(type)
        p.forEach { (k, v) ->
            if (k !in allowed) {
                errors += "$where: property '$k' is not in the documented schema for $type"
                return@forEach
            }
            val ok = when (k) {
                "id" -> ID.matches(v)
                "layout_width", "layout_height" -> SIZE.matches(v)
                in MARGINS, in V_PADDING, "paddingStart", "paddingEnd", "paddingLeft", "paddingRight" -> DP.matches(v)
                "backgroundColor", "textColor" -> COLOR.matches(v)
                "textSize" -> SP.matches(v)
                "gravity", "layout_gravity" -> v in GRAVITY
                "orientation" -> v in ORIENTATION
                "textStyle" -> v in TEXT_STYLE
                "scaleType" -> v in SCALE_TYPE
                "name" -> v.isNotEmpty() && (knownIcons == null || v in knownIcons)
                in RELATIVE_ID_REFS -> v in ids
                in RELATIVE_BOOLS -> v in BOOL
                else -> true // "text": any string
            }
            if (!ok) errors += "$where: invalid value for '$k': '$v'"
        }
    }
}
