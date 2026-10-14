package com.fentai.arcmenu.paper.render

import com.fentai.arcmenu.core.behavior.ArcitxBuffer
import com.fentai.arcmenu.core.model.ArcitxCaret
import com.fentai.arcmenu.core.model.ArcitxDisplay
import com.fentai.arcmenu.core.model.ArcitxInput
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArcitxTextTest {
    private val plain = PlainTextComponentSerializer.plainText()

    @Test
    fun `Chinese input and user supplied formatting and placeholders remain literal`() {
        val input = "&c中文 😀 %player_name% {caret} {input}"
        val buffer = ArcitxBuffer(ArcitxInput(
            "text", "submit", display = ArcitxDisplay(editing = "&a%prefix%{input}{caret}"),
            caret = ArcitxCaret("&e_"),
        ))
        buffer.openEditor()
        buffer.update(input)
        val resolved = mutableListOf<String>()

        val component = ArcitxText.render(buffer, true) { template ->
            resolved += template
            template.replace("%prefix%", "Value: ").replace("%player_name%", "expanded")
        }

        assertEquals(listOf("&a%prefix%{input}{caret}"), resolved)
        assertEquals("Value: ${input}_", plain.serialize(component))
        val colors = textColors(component)
        assertTrue(colors.dropLast(1).all { it == NamedTextColor.GREEN })
        assertEquals(NamedTextColor.YELLOW, colors.last())
    }

    @Test
    fun `caret content is authored formatting but its tokens are not expanded again`() {
        val buffer = ArcitxBuffer(ArcitxInput(
            "text", "submit", display = ArcitxDisplay(editing = "&a{input}{caret}", draft = "&7{input}"),
            caret = ArcitxCaret("&e{input}"),
        ))
        buffer.openEditor()
        buffer.update("中文{caret}")

        assertEquals("中文{caret}{input}", plain.serialize(ArcitxText.render(buffer, true) { it }))
        assertEquals("中文{caret}", plain.serialize(ArcitxText.render(buffer, false) { it }))
        buffer.closeEditor()
        assertEquals("中文{caret}", plain.serialize(ArcitxText.render(buffer, true) { it }))
        assertTrue(textColors(ArcitxText.render(buffer, true) { it }).all { it == NamedTextColor.GRAY })
    }

    @Test
    fun `mask counts Unicode code points and keeps the actual draft for submission`() {
        val input = "中文😀 a"
        val buffer = ArcitxBuffer(ArcitxInput("text", "submit", mask = "🔒"))
        buffer.openEditor()
        buffer.update(input)

        assertEquals("🔒".repeat(5) + "_", plain.serialize(ArcitxText.render(buffer, true) { it }))
        buffer.closeEditor()
        assertEquals(input, buffer.commit())
        assertEquals("🔒".repeat(5), plain.serialize(ArcitxText.render(buffer, true) { it }))
    }

    private fun textColors(component: Component, inherited: TextColor? = null): List<TextColor?> {
        val color = component.color() ?: inherited
        val own = if (component is TextComponent) List(component.content().length) { color } else emptyList()
        return own + component.children().flatMap { textColors(it, color) }
    }
}
