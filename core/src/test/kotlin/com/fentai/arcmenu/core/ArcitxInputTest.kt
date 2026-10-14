package com.fentai.arcmenu.core

import com.fentai.arcmenu.core.behavior.ArcitxBuffer
import com.fentai.arcmenu.core.behavior.ArcitxState
import com.fentai.arcmenu.core.behavior.ClickActions
import com.fentai.arcmenu.core.behavior.ConditionExpression
import com.fentai.arcmenu.core.behavior.ClickInput
import com.fentai.arcmenu.core.behavior.TellAction
import com.fentai.arcmenu.core.config.MenuFormatException
import com.fentai.arcmenu.core.config.MenuParser
import com.fentai.arcmenu.core.model.ArcitxCaret
import com.fentai.arcmenu.core.model.ArcitxDisplay
import com.fentai.arcmenu.core.model.ArcitxInput
import com.fentai.arcmenu.core.model.InteractionRegion
import kotlin.jvm.internal.DefaultConstructorMarker
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ArcitxInputTest {
    private val source = """
        schema-version: 1
        id: input
        frontend:
          form:
            type: group
            children:
              buffer: {type: text, content: Placeholder}
              decoration: {type: rectangle, width: 40, height: 10}
        backend:
          edit:
            type: Arcitx
            text: buffer
            submit: confirm
            width: 40
            height: 10
          confirm:
            x: 60
            width: 20
            height: 10
            actions: 'tell: submitted'
    """.trimIndent()

    private fun parse(value: String = source) = MenuParser().parse(value, "input.yml").definition

    private fun invalid(value: String, field: String) {
        val error = assertThrows<MenuFormatException> { parse(value) }
        assertTrue(error.message.orEmpty().contains("input.yml.backend.$field"), error.message)
    }

    @Test fun `native editor binds a nested text and distinct ordinary submit region`() {
        val menu = parse()
        assertEquals(ArcitxInput("buffer", "confirm"), menu.backend.first().arcitx)
        assertNull(menu.backend.last().arcitx)
        assertEquals(TellAction("submitted"), menu.backend.last().actions.matching(ClickInput.LEFT).single().actions.single())
        assertEquals(menu, parse(source.replace("type: Arcitx", "type: aRCITX")))
    }

    @Test fun `input settings and display phases are parsed without consuming normal actions`() {
        val menu = parse(source.replace("    text: buffer", """
            title: 'Enter a value'
            initial: '中文 😀'
            max-length: 12
            mask: '🔒'
            display:
              before: 'Start'
              editing: 'Typing {input}{caret}'
              draft: 'Review {input}'
              complete: 'Saved {input}'
            caret: {content: '|', interval: 6}
            actions: 'tell: editing'
            text: buffer
        """.trimIndent().prependIndent("    ")))
        assertEquals(ArcitxInput("buffer", "confirm", "Enter a value", "中文 😀", 12, "🔒",
            ArcitxDisplay("Start", "Typing {input}{caret}", "Review {input}", "Saved {input}"), ArcitxCaret("|", 6)), menu.backend.first().arcitx)
        assertEquals(TellAction("editing"), menu.backend.first().actions.matching(ClickInput.LEFT).single().actions.single())
    }

    @Test fun `bindings reject missing and nontext nodes and missing submit regions`() {
        for (id in listOf("missing", "form", "decoration", "confirm")) {
            invalid(source.replace("text: buffer", "text: $id"), "edit.text")
        }
        invalid(source.replace("submit: confirm", "submit: missing"), "edit.submit")
        invalid(source.replace("submit: confirm", "submit: edit"), "edit.submit")
        invalid(source.replace("submit: confirm", "submit: buffer"), "edit.submit")
    }

    @Test fun `edit and submit hit rectangles must remain separate including their edges`() {
        for (x in listOf(0, 10, 29, 30)) {
            invalid(source.replace("x: 60", "x: $x"), "edit.submit")
        }
        assertDoesNotThrow { parse(source.replace("x: 60", "x: 31")) }
        assertDoesNotThrow { parse(source.replace("x: 60", "x: 0\n    y: 11")) }
    }

    @Test fun `an Arcitx editor cannot act as the submit region of another editor`() {
        invalid(source.replace("    x: 60", "    type: Arcitx\n    text: buffer\n    submit: edit\n    x: 60"), "edit.submit")
    }

    @Test fun `each editor has exclusive text and submit bindings`() {
        val second = """
          second:
            type: Arcitx
            text: buffer
            submit: next
            x: 120
            width: 10
            height: 10
          next:
            x: 160
            width: 10
            height: 10
        """.trimIndent().prependIndent("  ")
        invalid("$source\n$second", "second.text")
        val extraText = source.replace("      buffer: {type: text, content: Placeholder}", "      buffer: {type: text, content: Placeholder}\n      other: {type: text, content: Other}")
        invalid("$extraText\n${second.replace("text: buffer", "text: other").replace("submit: next", "submit: confirm")}", "second.submit")
    }

    @Test fun `input limits mask and phase fields are strictly validated`() {
        for (value in listOf("0", "51", "1.5", "'many'")) {
            invalid(source.replace("    text: buffer", "    max-length: $value\n    text: buffer"), "edit.max-length")
        }
        invalid(source.replace("    text: buffer", "    mask: '**'\n    text: buffer"), "edit.mask")
        invalid(source.replace("    text: buffer", "    initial: '中文😀'\n    max-length: 2\n    text: buffer"), "edit.initial")
        invalid(source.replace("    text: buffer", "    caret: {interval: 0}\n    text: buffer"), "edit.caret.interval")
        invalid(source.replace("    text: buffer", "    display: {unknown: invalid}\n    text: buffer"), "edit.display.unknown")
        invalid(source.replace("type: Arcitx", "type: ANVIL"), "edit.type")
        invalid(source.replace("    type: Arcitx\n", ""), "edit.text")
    }

    @Test fun `former full and default region constructors retain their JVM signatures`() {
        val types = arrayOf(
            String::class.java, Double::class.javaPrimitiveType, Double::class.javaPrimitiveType,
            Double::class.javaPrimitiveType, Double::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            List::class.java, Int::class.javaPrimitiveType, ConditionExpression::class.java,
            ClickActions::class.java, List::class.java, List::class.java, List::class.java,
        )
        val full = InteractionRegion::class.java.getConstructor(*types)
        val legacy = full.newInstance("legacy", 0.0, 0.0, 10.0, 10.0, 0, emptyList<String>(), -1, null,
            ClickActions(), emptyList<Any>(), emptyList<Any>(), emptyList<Any>())
        assertNull(legacy.arcitx)
        val default = InteractionRegion::class.java.getConstructor(*types, Int::class.javaPrimitiveType, DefaultConstructorMarker::class.java)
        val defaulted = default.newInstance("legacy", 0.0, 0.0, 10.0, 10.0, 0, null, 0, null, null, null, null, null, 8160, null)
        assertEquals(-1, defaulted.tooltipUpdateTicks)
        assertTrue(defaulted.onHover.isEmpty())
        assertNull(defaulted.arcitx)
    }

    @Test fun `closing either mode editor keeps a Unicode draft without submitting`() {
        val buffer = ArcitxBuffer(ArcitxInput("text", "submit"))
        assertEquals(ArcitxState.BEFORE, buffer.state)
        assertEquals("&7Click to enter text", buffer.display())
        assertEquals("", buffer.openEditor())
        assertTrue(buffer.update("中文 😀 a b "))
        assertEquals("中文 😀 a b _", buffer.display())
        assertNull(buffer.commit())
        assertTrue(buffer.closeEditor())
        assertEquals(ArcitxState.DRAFT, buffer.state)
        assertNull(buffer.committedValue)
        assertEquals("中文 😀 a b ", buffer.display())
        assertFalse(buffer.update("ignored after editor close"))
        assertEquals("中文 😀 a b ", buffer.openEditor())
        assertTrue(buffer.update("再次修改 🇨🇳"))
        buffer.closeEditor()
        assertEquals("再次修改 🇨🇳", buffer.commit())
        assertEquals(ArcitxState.COMPLETE, buffer.state)
        assertEquals("再次修改 🇨🇳", buffer.committedValue)
        assertEquals("再次修改 🇨🇳", buffer.openEditor())
        buffer.update("反悔")
        buffer.closeEditor()
        assertEquals("再次修改 🇨🇳", buffer.committedValue)
        assertEquals("反悔", buffer.commit())
    }

    @Test fun `limits match native UTF16 while masks count code points and preserve surrogate pairs`() {
        val buffer = ArcitxBuffer(ArcitxInput("text", "submit", initial = "中😀", maxLength = 4, mask = "🔒"))
        buffer.openEditor()
        buffer.update("中😀文extra")
        assertEquals("中😀文", buffer.draft)
        assertEquals("🔒🔒🔒_", buffer.display())
        assertEquals("🔒🔒🔒", buffer.display(false))
        buffer.closeEditor()
        assertEquals("🔒🔒🔒", buffer.display())
        assertEquals("中😀文", buffer.commit())
        assertEquals("🔒🔒🔒", buffer.display())
        val split = ArcitxBuffer(ArcitxInput("text", "submit", maxLength = 3))
        split.openEditor()
        split.update("中文😀")
        assertEquals("中文", split.draft)
    }

    @Test fun `display only replaces template tokens and exposes a caret only while editing`() {
        val buffer = ArcitxBuffer(ArcitxInput("text", "submit", display = ArcitxDisplay(
            "Before {caret}", "Editing {input}{caret}", "Draft {input}{caret}", "Complete {input}{caret}")))
        assertEquals("Before ", buffer.display())
        buffer.openEditor()
        buffer.update("{caret}")
        assertEquals("Editing {caret}_", buffer.display())
        buffer.closeEditor()
        assertEquals("Draft {caret}", buffer.display())
        buffer.commit()
        assertEquals("Complete {caret}", buffer.display())
    }

    @Test fun `initial value survives reopening and untouched input keeps its before display`() {
        val buffer = ArcitxBuffer(ArcitxInput("text", "submit", initial = "initial"))
        assertEquals("initial", buffer.openEditor())
        assertFalse(buffer.update("initial"))
        buffer.closeEditor()
        assertEquals(ArcitxState.BEFORE, buffer.state)
        assertEquals("initial", buffer.openEditor())
        buffer.closeEditor()
        assertEquals("initial", buffer.commit())
    }

    @Test fun `unchanged reopening restores its preceding before draft or complete display state`() {
        val buffer = ArcitxBuffer(ArcitxInput("text", "submit"))
        buffer.openEditor()
        buffer.closeEditor()
        assertEquals(ArcitxState.BEFORE, buffer.state)
        buffer.openEditor()
        buffer.update("中文")
        buffer.closeEditor()
        assertEquals(ArcitxState.DRAFT, buffer.state)
        buffer.openEditor()
        buffer.closeEditor()
        assertEquals(ArcitxState.DRAFT, buffer.state)
        buffer.commit()
        buffer.openEditor()
        buffer.closeEditor()
        assertEquals(ArcitxState.COMPLETE, buffer.state)
    }
}
