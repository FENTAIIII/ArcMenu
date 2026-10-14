package com.fentai.arcmenu.paper.editor

import com.fentai.arcmenu.core.config.MenuParser
import com.fentai.arcmenu.core.model.ArcitxInput
import com.fentai.arcmenu.protocol.EditorProtocol
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class EditorArcitxTest {
    @TempDir
    lateinit var directory: Path

    private val source = """
        schema-version: 1
        id: arcitx-editor
        frontend:
          form:
            type: group
            children:
              buffer: {type: text, content: Placeholder}
              other: {type: text, content: Other}
        backend:
          edit:
            type: Arcitx
            text: buffer
            submit: confirm
            title: 'Enter a value'
            initial: '中文 😀'
            max-length: 30
            mask: '*'
            display:
              before: 'Idle'
              editing: '{input}{caret}'
              draft: 'Review {input}'
              complete: 'Complete {input}'
            caret: {content: '_', interval: 7}
            x: 0 # preserve coordinate comment
            y: 0
            width: 40
            height: 10
            actions: 'tell: Editing'
          confirm:
            x: 80
            width: 30
            height: 10
            actions: 'tell: Submitted'
          spare:
            x: 160
            width: 20
            height: 10
          unused:
            x: 240
            width: 20
            height: 10
    """.trimIndent() + "\n"

    private fun createDraft(initial: String = source): EditorDraft {
        val file = directory.resolve("arcitx-editor.yml")
        Files.writeString(file, initial)
        val parser = MenuParser()
        val definition = parser.parse(initial).definition
        return EditorDraft(UUID.randomUUID(), file, definition.id, initial, definition, {},
            { parser.parse(it).definition }, directory.resolve("backup.yml"))
    }

    private fun properties(draft: EditorDraft, id: String = "edit") =
        draft.snapshot("26.3").backend().single { it.id() == id }.properties().associateBy { it.key() }

    @Test fun `inspector exposes native bindings and mapped nested settings`() {
        val draft = createDraft()
        val fields = properties(draft)
        assertEquals(listOf("region", "Arcitx"), fields.getValue("type").choices())
        assertEquals("Arcitx", fields.getValue("type").value())
        assertEquals("buffer", fields.getValue("arcitx-text").value())
        assertEquals("confirm", fields.getValue("arcitx-submit").value())
        assertEquals("{input}{caret}", fields.getValue("arcitx-display-editing").value())
        assertEquals("中文 😀", fields.getValue("arcitx-initial").value())
        assertEquals(EditorProtocol.PROPERTY_INTEGER, fields.getValue("arcitx-max-length").type())
        assertEquals(EditorProtocol.PROPERTY_INTEGER, fields.getValue("arcitx-caret-interval").type())
        assertFalse(properties(draft, "confirm").keys.any { it.startsWith("arcitx-") })
    }

    @Test fun `mapped properties roundtrip through real YAML without overwriting sibling phases or actions`() {
        val draft = createDraft()
        val actions = draft.definition.backend.first().actions
        val changes = linkedMapOf(
            "arcitx-title" to "Input title", "arcitx-initial" to "汉字 😀",
            "arcitx-max-length" to "40", "arcitx-mask" to "●",
            "arcitx-display-before" to "Before", "arcitx-display-editing" to "Typing {input}{caret}",
            "arcitx-display-draft" to "Draft {input}", "arcitx-display-complete" to "Done {input}",
            "arcitx-caret-content" to "|", "arcitx-caret-interval" to "12",
        )
        changes.forEach { (key, value) -> draft.setProperty(draft.revision, EditorProtocol.TAB_BACKEND, "edit", key, value) }
        val input = draft.definition.backend.first().arcitx!!
        assertEquals("Input title", input.title)
        assertEquals("汉字 😀", input.initial)
        assertEquals(40, input.maxLength)
        assertEquals("●", input.mask)
        assertEquals("Typing {input}{caret}", input.display.editing)
        assertEquals("Draft {input}", input.display.draft)
        assertEquals("Done {input}", input.display.complete)
        assertEquals("|", input.caret.content)
        assertEquals(12, input.caret.interval)
        assertEquals(actions, draft.definition.backend.first().actions)
        assertFalse(Regex("(?m)^\\s+arcitx-[a-z-]+:").containsMatchIn(draft.source))
        changes.forEach { (key, value) -> assertEquals(value, properties(draft).getValue(key).value()) }
        draft.save(draft.revision)
        assertEquals(draft.definition, MenuParser().parse(Files.readString(draft.file)).definition)
        draft.undo(draft.revision)
        assertEquals(7, draft.definition.backend.first().arcitx!!.caret.interval)
        assertEquals("|", draft.definition.backend.first().arcitx!!.caret.content)
        draft.redo(draft.revision)
        assertEquals(input, draft.definition.backend.first().arcitx)
    }

    @Test fun `renaming text and submit identities updates bindings atomically and remains undoable`() {
        val draft = createDraft()
        draft.setProperty(draft.revision, EditorProtocol.TAB_FRONTEND, "buffer", "id", "renamed-text")
        assertEquals("renamed-text", draft.definition.backend.first().arcitx!!.textId)
        draft.setProperty(draft.revision, EditorProtocol.TAB_BACKEND, "confirm", "id", "renamed-submit")
        assertEquals("renamed-submit", draft.definition.backend.first().arcitx!!.submitId)
        draft.save(draft.revision)
        assertEquals(draft.definition, MenuParser().parse(Files.readString(draft.file)).definition)
        draft.undo(draft.revision)
        assertEquals("confirm", draft.definition.backend.first().arcitx!!.submitId)
        assertEquals("renamed-text", draft.definition.backend.first().arcitx!!.textId)
        draft.undo(draft.revision)
        assertEquals(MenuParser().parse(source).definition, draft.definition)
    }

    @Test fun `moving resizing and saving retain all raw native input settings and comments`() {
        val draft = createDraft()
        val input = draft.definition.backend.first().arcitx
        draft.move(draft.revision, EditorProtocol.TAB_BACKEND, "edit", 5.0, 3.0)
        draft.resize(draft.revision, EditorProtocol.TAB_BACKEND, "edit", 20.0, 11.0)
        assertEquals(input, draft.definition.backend.first().arcitx)
        assertTrue(draft.source.contains("# preserve coordinate comment"))
        assertTrue(draft.source.contains("draft: 'Review {input}'"))
        draft.save(draft.revision)
        assertEquals(draft.definition, MenuParser().parse(Files.readString(draft.file)).definition)
    }

    @Test fun `bound editor duplication refuses before changing document revision or history`() {
        val draft = createDraft()
        val error = assertThrows(IllegalArgumentException::class.java) {
            draft.duplicate(0, EditorProtocol.TAB_BACKEND, listOf("spare", "edit"), "", true)
        }
        assertTrue(error.message.orEmpty().contains("cannot be duplicated"), error.message)
        assertEquals(0, draft.revision)
        assertEquals(source, draft.source)
        assertFalse(draft.dirty)
        assertThrows(IllegalArgumentException::class.java) { draft.undo(0) }
    }

    @Test fun `explicit YAML creates an independently bound native editor`() {
        val draft = createDraft()
        val id = draft.create(0, EditorProtocol.TAB_BACKEND, EditorProtocol.KIND_REGION, "", """
            type: Arcitx
            text: other
            submit: spare
            x: 200
            width: 20
            height: 10
        """.trimIndent())
        assertEquals(ArcitxInput("other", "spare"), draft.definition.backend.single { it.id == id }.arcitx)
        draft.save(draft.revision)
        assertEquals(draft.definition, MenuParser().parse(Files.readString(draft.file)).definition)
    }

    @Test fun `type conversion chooses unused bindings without taking a business submit action`() {
        val draft = createDraft()
        draft.setProperty(0, EditorProtocol.TAB_BACKEND, "spare", "type", "Arcitx")
        assertEquals(ArcitxInput("other", "unused"), draft.definition.backend.single { it.id == "spare" }.arcitx)
        assertNull(draft.definition.backend.single { it.id == "confirm" }.arcitx)
        draft.setProperty(draft.revision, EditorProtocol.TAB_BACKEND, "spare", "type", "region")
        assertNull(draft.definition.backend.single { it.id == "spare" }.arcitx)
        assertFalse(properties(draft, "spare").keys.any { it.startsWith("arcitx-") })
        val revision = draft.revision
        val withoutSpare = createDraft(source.substringBefore("  unused:") + """
          business:
            x: 240
            width: 20
            height: 10
            actions: 'tell: Unrelated action'
        """.trimIndent().prependIndent("  ") + "\n")
        assertThrows(IllegalArgumentException::class.java) {
            withoutSpare.setProperty(0, EditorProtocol.TAB_BACKEND, "spare", "type", "Arcitx")
        }
        assertEquals(0, withoutSpare.revision)
        assertEquals(revision, draft.revision)
    }

    @Test fun `invalid rebindings and integer settings preserve the authoritative draft`() {
        val draft = createDraft()
        for ((key, value) in listOf("arcitx-text" to "form", "arcitx-submit" to "edit",
            "arcitx-max-length" to "1.5", "arcitx-caret-interval" to "0")) {
            assertThrows(IllegalArgumentException::class.java) {
                draft.setProperty(0, EditorProtocol.TAB_BACKEND, "edit", key, value)
            }
            assertEquals(0, draft.revision)
            assertEquals(source, draft.source)
        }
    }
}
