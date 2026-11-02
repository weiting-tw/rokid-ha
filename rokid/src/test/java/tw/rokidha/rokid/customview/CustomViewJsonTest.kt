package tw.rokidha.rokid.customview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.rokidha.domain.glasses.DisplayLine
import tw.rokidha.domain.glasses.DisplayScreen

class CustomViewJsonTest {

    @Test
    fun `serialises the official v1_0_4 open example structure`() {
        val tree = CustomViews.linearLayout(
            id = "root",
            orientation = "vertical",
            gravity = "center_horizontal",
            backgroundColor = "#FF000000",
            box = CustomViews.Box(marginTop = "160dp", marginBottom = "80dp"),
            children = listOf(
                CustomViews.textView(
                    id = "textView",
                    text = "Hello World",
                    width = "wrap_content",
                    textColor = "#00FF00",
                    textSize = "16sp",
                    gravity = "center",
                    textStyle = "bold",
                    box = CustomViews.Box(paddingStart = "16dp", paddingEnd = "16dp"),
                ),
            ),
        )
        assertEquals(emptyList<String>(), CustomViewValidator.validateTree(tree))
        assertEquals(
            """{"type":"LinearLayout","props":{"id":"root","layout_width":"match_parent","layout_height":"match_parent",""" +
                """"orientation":"vertical","gravity":"center_horizontal","backgroundColor":"#FF000000",""" +
                """"marginTop":"160dp","marginBottom":"80dp"},"children":[{"type":"TextView","props":{"id":"textView",""" +
                """"layout_width":"wrap_content","layout_height":"wrap_content","text":"Hello World","textColor":"#00FF00",""" +
                """"textSize":"16sp","gravity":"center","textStyle":"bold","paddingStart":"16dp","paddingEnd":"16dp"}}]}""",
            tree.toJson(),
        )
    }

    @Test
    fun `linear layout horizontal padding uses paddingLeft and paddingRight keys`() {
        val json = CustomViews.linearLayout(id = "r", box = CustomViews.Box(paddingStart = "8dp", paddingEnd = "9dp")).toJson()
        assertTrue(json.contains("\"paddingLeft\":\"8dp\""))
        assertTrue(json.contains("\"paddingRight\":\"9dp\""))
    }

    @Test
    fun `update array matches official example`() {
        val json = CustomViewUpdate.toJson(
            listOf(
                CustomViewUpdate("textView", mapOf("text" to "Hello Rokid 1")),
                CustomViewUpdate("imageView", mapOf("name" to "icon2")),
            ),
        )
        assertEquals(
            """[{"action":"update","id":"textView","props":{"text":"Hello Rokid 1"}},""" +
                """{"action":"update","id":"imageView","props":{"name":"icon2"}}]""",
            json,
        )
    }

    @Test
    fun `escapes quotes backslashes and control characters, keeps unicode`() {
        val json = CustomViews.textView(id = "t", text = "26.0°C \"客廳\" a\\b\n").toJson()
        assertTrue(json, json.contains(""""text":"26.0°C \"客廳\" a\\b\n""""))
    }

    @Test
    fun `icons array`() {
        assertEquals(
            """[{"name":"icon1","data":"AAA="}]""",
            CustomViewIcon.toJson(listOf(CustomViewIcon("icon1", "AAA="))),
        )
    }

    @Test
    fun `validator rejects undocumented keys, bad units and duplicate ids`() {
        val bad = CustomViewNode(
            "LinearLayout",
            mapOf("id" to "root", "layout_width" to "100px", "layout_height" to "match_parent", "orientation" to "vertical"),
            listOf(
                CustomViewNode("TextView", mapOf("id" to "a", "layout_width" to "match_parent", "layout_height" to "wrap_content", "text" to "x", "textSize" to "16dp")),
                CustomViewNode("TextView", mapOf("id" to "a", "layout_width" to "match_parent", "layout_height" to "wrap_content", "text" to "y", "onClick" to "z")),
                CustomViewNode("WebView", mapOf("id" to "w")),
            ),
        )
        val errors = CustomViewValidator.validateTree(bad)
        assertTrue(errors.toString(), errors.any { "duplicate id 'a'" in it })
        assertTrue(errors.toString(), errors.any { "layout_width" in it && "100px" in it })
        assertTrue(errors.toString(), errors.any { "textSize" in it })
        assertTrue(errors.toString(), errors.any { "onClick" in it })
        assertTrue(errors.toString(), errors.any { "unsupported type 'WebView'" in it })
    }

    @Test
    fun `validator requires orientation on LinearLayout and icons to be known`() {
        val tree = CustomViewNode(
            "LinearLayout",
            mapOf("id" to "root", "layout_width" to "match_parent", "layout_height" to "match_parent"),
            listOf(CustomViews.imageView(id = "img", iconName = "bulb", width = "48dp", height = "48dp")),
        )
        val errors = CustomViewValidator.validateTree(tree, knownIcons = setOf("other"))
        assertTrue(errors.toString(), errors.any { "missing orientation" in it })
        assertTrue(errors.toString(), errors.any { "'name'" in it })
    }

    @Test
    fun `updates must target ids of the open tree`() {
        val tree = DisplayScreenRenderer().render(
            DisplayScreen("home", listOf(DisplayLine("title", "HOME", DisplayLine.Style.TITLE), DisplayLine("temp", "26.0°C"))),
        )
        assertEquals(emptyList<String>(), CustomViewValidator.validateTree(tree))
        assertEquals(
            emptyList<String>(),
            CustomViewValidator.validateUpdates(listOf(CustomViewUpdate("temp", mapOf("text" to "25.5°C"))), tree),
        )
        val errors = CustomViewValidator.validateUpdates(listOf(CustomViewUpdate("humidity", mapOf("text" to "58%"))), tree)
        assertTrue(errors.toString(), errors.any { "unknown id 'humidity'" in it })
    }
}
