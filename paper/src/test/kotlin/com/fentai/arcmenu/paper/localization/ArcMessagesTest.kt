package com.fentai.arcmenu.paper.localization

import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArcMessagesTest {
    @Test
    fun `plugin prefix has the requested hex gradient and leaves the body unstyled`() {
        val component = ArcMessages.prefixed("Saved")
        assertEquals("ArcMenu>> Saved", plain(component))
        val prefix = component.children().first()
        val letters = flatten(prefix).filter { it is TextComponent && it.content() in listOf("A", "r", "c", "M", "e", "n", "u") }
        assertEquals(listOf("#3c73ff", "#4983ff", "#5593ff", "#62a4ff", "#6fb4ff", "#7bc4ff", "#88d4ff"), letters.map { it.color()?.asHexString() })
        assertTrue(letters.all { it.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE })
        val body = component.children().last()
        assertEquals(null, body.color())
        assertFalse(body.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE)
    }

    @Test
    fun `message colors accept ampersand hexadecimal and section codes`() {
        val component = ArcMessages.prefixed("&cDenied §aReady &#123456Hex")
        assertEquals("ArcMenu>> Denied Ready Hex", plain(component))
        val body = flatten(component.children().last()).filterIsInstance<TextComponent>()
        assertEquals(listOf("#ff5555", "#55ff55", "#123456"), body.mapNotNull { it.color()?.asHexString() })
    }

    private fun flatten(component: Component): List<Component> = listOf(component) + component.children().flatMap(::flatten)
    private fun plain(component: Component): String = flatten(component).filterIsInstance<TextComponent>().joinToString("") { it.content() }
}
