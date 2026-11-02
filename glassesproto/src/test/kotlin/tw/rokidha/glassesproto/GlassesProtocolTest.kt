package tw.rokidha.glassesproto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.rokidha.domain.glasses.GlassesInput.Kind
import tw.rokidha.glassesproto.GlassesProtocol.Hello
import tw.rokidha.glassesproto.GlassesProtocol.InputEvent
import tw.rokidha.glassesproto.GlassesProtocol.Line
import tw.rokidha.glassesproto.GlassesProtocol.UiFrame

class GlassesProtocolTest {
    @Test fun `messages round trip`() {
        val frame = UiFrame(7, listOf(Line("title", "HOME", "TITLE"), Line("e0", "> Living Light: ON", "BODY")), "HA ONLINE")
        assertEquals(frame, GlassesProtocol.decode(GlassesProtocol.encode(frame)))
        val input = InputEvent("s1a2", 3, Kind.SELECT, "SPRITE_BUTTON_CLICK")
        assertEquals(input, GlassesProtocol.decode(GlassesProtocol.encode(input)))
        val diag = InputEvent("s1a2", 4, null, "SPRITE_BUTTON_DOUBLE_CLICK")
        assertEquals(diag, GlassesProtocol.decode(GlassesProtocol.encode(diag)))
        assertEquals(Hello("s1a2"), GlassesProtocol.decode(GlassesProtocol.encode(Hello("s1a2"))))
    }

    @Test fun `glasses cannot send PRIMARY, unknown kinds, other versions or malformed data`() {
        assertNull(GlassesProtocol.decode(listOf("in", 1L, "s", 1L, "PRIMARY", "x")))
        assertNull(GlassesProtocol.decode(listOf("in", 1L, "s", 1L, "call_service", "x")))
        assertNull(GlassesProtocol.decode(listOf("in", 2L, "s", 1L, "SELECT", "x")))
        assertNull(GlassesProtocol.decode(listOf("in", 1L, "bad session!", 1L, "SELECT", "x")))
        assertNull(GlassesProtocol.decode(listOf("in", 1L, "s", "1", "SELECT", "x")))
        assertNull(GlassesProtocol.decode(listOf("ui", 1L, 1L, 99L)))
        assertNull(GlassesProtocol.decode(listOf("ui", 1L, 1L, 1L, "bad id", "t", "BODY", "")))
        assertNull(GlassesProtocol.decode(listOf("evil")))
        assertNull(GlassesProtocol.decode(emptyList()))
    }

    @Test fun `frames are size limited`() {
        val big = UiFrame(1, (1..40).map { Line("l$it", "x".repeat(500), "BODY") }, "s")
        val decoded = GlassesProtocol.decode(GlassesProtocol.encode(big)) as UiFrame
        assertEquals(GlassesProtocol.MAX_LINES, decoded.lines.size)
        assertTrue(decoded.lines.all { it.text.length == GlassesProtocol.MAX_TEXT })
    }

    @Test fun `key map follows the documented broadcasts and leaves system keys alone`() {
        assertEquals(Kind.SELECT, GlassesKeyMap.kindForBroadcast(GlassesKeyMap.CLICK))
        assertEquals(Kind.NEXT, GlassesKeyMap.kindForBroadcast(GlassesKeyMap.TWO_FINGER_SWIPE_FORWARD))
        assertEquals(Kind.PREVIOUS, GlassesKeyMap.kindForBroadcast(GlassesKeyMap.TWO_FINGER_SWIPE_BACK))
        assertEquals(Kind.BACK, GlassesKeyMap.kindForBroadcast(GlassesKeyMap.LONG_PRESS))
        assertNull(GlassesKeyMap.kindForBroadcast(GlassesKeyMap.DOUBLE_CLICK))
        assertFalse(GlassesKeyMap.consume(GlassesKeyMap.DOUBLE_CLICK))
        assertFalse(GlassesKeyMap.consume(GlassesKeyMap.AI_START))
        assertTrue(GlassesKeyMap.consume(GlassesKeyMap.CLICK))
        assertEquals("SPRITE_BUTTON_CLICK", GlassesKeyMap.shortName(GlassesKeyMap.CLICK))
        assertEquals(11, GlassesKeyMap.ACTIONS.size)
    }
}
