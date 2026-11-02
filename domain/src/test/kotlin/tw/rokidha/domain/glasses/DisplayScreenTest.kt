package tw.rokidha.domain.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayScreenTest {
    @Test(expected = IllegalArgumentException::class)
    fun `rejects duplicate line ids`() {
        DisplayScreen("home", listOf(DisplayLine("a", "1"), DisplayLine("a", "2")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects ids outside the CustomView id charset`() {
        DisplayLine("living-room", "x")
    }

    @Test
    fun `link ready requires transport and glasses bluetooth`() {
        assertFalse(GlassesLinkState(transportConnected = true).linkReady)
        assertFalse(GlassesLinkState(glassesConnected = true).linkReady)
        assertTrue(GlassesLinkState(transportConnected = true, glassesConnected = true).linkReady)
    }
}
