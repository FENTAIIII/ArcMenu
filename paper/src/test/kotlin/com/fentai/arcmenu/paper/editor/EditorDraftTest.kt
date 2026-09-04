package com.fentai.arcmenu.paper.editor

import com.fentai.arcmenu.core.config.MenuParser
import com.fentai.arcmenu.core.config.TemplateCatalog
import com.fentai.arcmenu.protocol.EditorProtocol
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class EditorDraftTest {
    @TempDir
    lateinit var directory: Path

    private val source = """
        # author comment must survive scalar editing
        schema-version: 1
        id: editor-test
        canvas:
          width: 320
          height: 180
          pixels-per-block: 100
          distance: 3
        frontend:
          rotated-group:
            type: group
            offset: {x: 10, y: 20, z: 1} # keep group comment
            rotation: {z: 90}
            children:
              panel:
                type: rectangle
                width: 40
                height: 20
                offset:
                  x: 5 # keep x comment
                  y: 0
                color: '#123456'
        backend:
          panel-area:
            x: 0 # keep backend comment
            y: 0
            width: 40
            height: 20
            tooltip: &tooltip
              - 'business field'
            actions:
              right: 'tell: unchanged action'
        events:
          open: 'tell: open'
    """.trimIndent() + "\n"

    private fun draft(): EditorDraft {
        val file = directory.resolve("editor-test.yml")
        Files.writeString(file, source)
        val parser = MenuParser()
        val definition = parser.parse(source, file.fileName.toString()).definition
        return EditorDraft(
            UUID.randomUUID(), file, definition.id, source, definition,
            validate = {}, parseSaved = { parser.parse(it, file.fileName.toString()).definition },
        )
    }

    @Test
    fun `frontend child drag under rotated group reaches requested global center and preserves yaml`() {
        val draft = draft()
        val moved = draft.move(0, EditorProtocol.TAB_FRONTEND, "panel", 37.5, -12.25)
        assertEquals(37.5, moved.x, 1e-8)
        assertEquals(-12.25, moved.y, 1e-8)
        assertEquals(1, draft.revision)
        assertTrue(draft.dirty)

        draft.save(1)
        val saved = Files.readString(draft.file)
        assertTrue(saved.contains("# author comment must survive scalar editing"))
        assertTrue(saved.contains("# keep group comment"))
        assertTrue(saved.contains("# keep x comment"))
        assertTrue(saved.contains("tooltip: &tooltip"))
        assertTrue(saved.contains("right: 'tell: unchanged action'"))
        assertFalse(draft.dirty)
        assertTrue(draft.saved)

        val reparsed = MenuParser().parse(saved).definition
        assertEquals(draft.definition, reparsed)
    }

    @Test
    fun `backend remains axis aligned and revision undo redo are authoritative`() {
        val draft = draft()
        val moved = draft.move(0, EditorProtocol.TAB_BACKEND, "panel-area", 12.0, -8.0)
        assertEquals(12.0, moved.x)
        assertEquals(-8.0, moved.y)
        assertThrows(IllegalArgumentException::class.java) {
            draft.move(0, EditorProtocol.TAB_BACKEND, "panel-area", 0.0, 0.0)
        }

        val resized = draft.resize(1, EditorProtocol.TAB_BACKEND, "panel-area", 50.0, 30.0)
        assertEquals(76.0, resized.width)
        assertEquals(76.0, resized.height)
        val backend = draft.snapshot("26.1.2").backend().single()
        assertEquals(0.0, backend.rotationZ())

        draft.undo(2)
        assertEquals(40.0, draft.geometry("panel-area", EditorProtocol.TAB_BACKEND).width)
        draft.redo(3)
        assertEquals(76.0, draft.geometry("panel-area", EditorProtocol.TAB_BACKEND).width)
    }

    @Test
    fun `frontend corner resize changes local axes under a rotated parent and remains undoable`() {
        val draft = draft()
        val before = draft.geometry("panel", EditorProtocol.TAB_FRONTEND)
        assertEquals(20.0, before.width, 1e-8)
        assertEquals(40.0, before.height, 1e-8)

        val resized = draft.resize(0, EditorProtocol.TAB_FRONTEND, "panel",
            before.x + 30.0, before.y - 40.0)
        assertEquals(60.0, resized.width, 1e-7)
        assertEquals(80.0, resized.height, 1e-7)
        val properties = draft.snapshot("26.1.2").frontend().first { it.id() == "panel" }.properties()
        assertEquals("2", properties.first { it.key() == "scale.x" }.value())
        assertEquals("3", properties.first { it.key() == "scale.y" }.value())
        draft.save(1)
        assertTrue(Files.readString(draft.file).contains("# keep x comment"))

        draft.undo(1)
        assertEquals(20.0, draft.geometry("panel", EditorProtocol.TAB_FRONTEND).width, 1e-8)
        assertEquals(40.0, draft.geometry("panel", EditorProtocol.TAB_FRONTEND).height, 1e-8)
    }

    @Test
    fun `live frontend resize samples form one undo transaction`() {
        val draft = draft()
        val center = draft.geometry("panel", EditorProtocol.TAB_FRONTEND)
        draft.resize(0, EditorProtocol.TAB_FRONTEND, "panel", center.x + 15.0, center.y - 25.0, 81, false)
        draft.resize(1, EditorProtocol.TAB_FRONTEND, "panel", center.x + 25.0, center.y - 35.0, 81, false)
        val final = draft.resize(2, EditorProtocol.TAB_FRONTEND, "panel",
            center.x + 25.0, center.y - 35.0, 81, true)
        assertEquals(50.0, final.width, 1e-7)
        assertEquals(70.0, final.height, 1e-7)

        draft.undo(2)
        assertEquals(20.0, draft.geometry("panel", EditorProtocol.TAB_FRONTEND).width, 1e-8)
        assertEquals(40.0, draft.geometry("panel", EditorProtocol.TAB_FRONTEND).height, 1e-8)
    }

    @Test
    fun `live drag samples share one undo transaction and final duplicate only closes gesture`() {
        val draft = draft()
        draft.move(0, EditorProtocol.TAB_BACKEND, "panel-area", 5.0, -2.0, 41, false)
        draft.move(1, EditorProtocol.TAB_BACKEND, "panel-area", 11.0, -7.0, 41, false)
        val final = draft.move(2, EditorProtocol.TAB_BACKEND, "panel-area", 11.0, -7.0, 41, true)

        assertEquals(2, draft.revision)
        assertEquals(11.0, final.x)
        assertEquals(-7.0, final.y)
        draft.undo(2)
        assertEquals(0.0, draft.geometry("panel-area", EditorProtocol.TAB_BACKEND).x)
        assertEquals(0.0, draft.geometry("panel-area", EditorProtocol.TAB_BACKEND).y)
        draft.redo(3)
        assertEquals(11.0, draft.geometry("panel-area", EditorProtocol.TAB_BACKEND).x)
        assertEquals(-7.0, draft.geometry("panel-area", EditorProtocol.TAB_BACKEND).y)
    }

    @Test
    fun `save refuses to overwrite an externally changed menu`() {
        val draft = draft()
        draft.move(0, EditorProtocol.TAB_BACKEND, "panel-area", 4.0, 5.0)
        Files.writeString(draft.file, source + "# external edit\n")

        val error = assertThrows(IllegalArgumentException::class.java) { draft.save(1) }
        assertTrue(error.message.orEmpty().contains("外部修改"))
        assertTrue(Files.readString(draft.file).endsWith("# external edit\n"))
    }

    @Test
    fun `structure properties and template copies remain valid yaml with deduplicated keys`() {
        val draft = draft()
        val templateSource = draft.templateSource(0, "rotated-group", "card-template")
        val template = TemplateCatalog().parse(templateSource)
        val copied = draft.instantiate(0, template)
        assertEquals("rotated-group2", copied)
        assertTrue(draft.snapshot("26.1.2").frontend().any { it.id() == "panel2" })

        val created = draft.create(1, EditorProtocol.TAB_FRONTEND, EditorProtocol.KIND_TEXT, copied)
        assertEquals("text", created)
        draft.setProperty(2, EditorProtocol.TAB_FRONTEND, created, "content", "&eEdited")
        draft.setProperty(3, EditorProtocol.TAB_FRONTEND, created, "offset.x", "17.5")
        assertEquals("&eEdited", draft.snapshot("26.1.2").frontend().first { it.id() == created }
            .properties().first { it.key() == "content" }.value())

        val image = draft.create(4, EditorProtocol.TAB_FRONTEND, EditorProtocol.KIND_IMAGE, copied, "/ui/card.png")
        assertEquals("/ui/card.png", draft.snapshot("26.1.2").frontend().first { it.id() == image }
            .properties().first { it.key() == "source" }.value())

        val region = draft.create(5, EditorProtocol.TAB_BACKEND, EditorProtocol.KIND_REGION, "")
        draft.setProperty(6, EditorProtocol.TAB_BACKEND, region, "tooltip", "line one\nline two")
        draft.delete(7, EditorProtocol.TAB_BACKEND, region)
        draft.save(8)
        assertEquals(draft.definition, MenuParser().parse(Files.readString(draft.file)).definition)
        assertEquals(source, Files.readString(draft.file.resolveSibling("editor-test.yml.arcmenu-backup")))
    }

    @Test
    fun `multi selection duplicates groups and deletes ancestor once`() {
        val draft = draft()
        val copied = draft.duplicate(0, EditorProtocol.TAB_FRONTEND, listOf("rotated-group", "panel"), "", true)
        assertEquals(listOf("rotated-group2"), copied)
        val snapshot = draft.snapshot("26.1.2")
        assertTrue(snapshot.frontend().any { it.id() == "rotated-group2" })
        assertTrue(snapshot.frontend().any { it.id() == "panel2" && it.parentId() == "rotated-group2" })

        draft.deleteMany(1, EditorProtocol.TAB_FRONTEND, listOf("rotated-group", "panel"))
        assertFalse(draft.snapshot("26.1.2").frontend().any { it.id() == "rotated-group" || it.id() == "panel" })
        assertTrue(draft.snapshot("26.1.2").frontend().any { it.id() == "rotated-group2" })
    }

    @Test
    fun `same level elements can be grouped and reordered atomically`() {
        val draft = draft()
        val first = draft.create(0, EditorProtocol.TAB_FRONTEND, EditorProtocol.KIND_TEXT, "rotated-group")
        val second = draft.create(1, EditorProtocol.TAB_FRONTEND, EditorProtocol.KIND_IMAGE, "rotated-group", "/ui/test.png")
        draft.reorder(2, EditorProtocol.TAB_FRONTEND, listOf(second), first)
        val group = draft.group(3, listOf("panel", second))
        assertEquals("group", group)
        val nodes = draft.snapshot("26.1.2").frontend()
        assertEquals(group, nodes.first { it.id() == "panel" }.parentId())
        assertEquals(group, nodes.first { it.id() == second }.parentId())
        assertEquals("rotated-group", nodes.first { it.id() == first }.parentId())
        draft.undo(4)
        assertEquals("rotated-group", draft.snapshot("26.1.2").frontend().first { it.id() == "panel" }.parentId())
    }

    @Test
    fun `multi selection can move from different levels into an existing group without moving on screen`() {
        val draft = draft()
        val target = draft.create(0, EditorProtocol.TAB_FRONTEND, EditorProtocol.KIND_GROUP, "")
        draft.setProperty(1, EditorProtocol.TAB_FRONTEND, target, "rotation.z", "30")
        draft.setProperty(2, EditorProtocol.TAB_FRONTEND, target, "scale.x", "2")
        draft.setProperty(3, EditorProtocol.TAB_FRONTEND, target, "scale.y", "2")
        val text = draft.create(4, EditorProtocol.TAB_FRONTEND, EditorProtocol.KIND_TEXT, "")
        draft.setProperty(5, EditorProtocol.TAB_FRONTEND, text, "offset.x", "-33")
        draft.setProperty(6, EditorProtocol.TAB_FRONTEND, text, "offset.y", "14")
        val beforePanel = draft.geometry("panel", EditorProtocol.TAB_FRONTEND)
        val beforeText = draft.geometry(text, EditorProtocol.TAB_FRONTEND)

        draft.reparent(7, listOf("panel", text), target, "")

        val nodes = draft.snapshot("26.1.2").frontend()
        assertEquals(target, nodes.first { it.id() == "panel" }.parentId())
        assertEquals(target, nodes.first { it.id() == text }.parentId())
        val afterPanel = draft.geometry("panel", EditorProtocol.TAB_FRONTEND)
        val afterText = draft.geometry(text, EditorProtocol.TAB_FRONTEND)
        assertEquals(beforePanel.x, afterPanel.x, 1e-6)
        assertEquals(beforePanel.y, afterPanel.y, 1e-6)
        assertEquals(beforePanel.width, afterPanel.width, 1e-6)
        assertEquals(beforePanel.height, afterPanel.height, 1e-6)
        assertEquals(beforeText.x, afterText.x, 1e-6)
        assertEquals(beforeText.y, afterText.y, 1e-6)
        assertEquals(beforeText.width, afterText.width, 1e-6)
        assertEquals(beforeText.height, afterText.height, 1e-6)

        draft.reparent(8, listOf("panel", text), "", "rotated-group")
        val movedOut = draft.snapshot("26.1.2").frontend()
        assertEquals("", movedOut.first { it.id() == "panel" }.parentId())
        assertEquals("", movedOut.first { it.id() == text }.parentId())
        assertTrue(movedOut.indexOfFirst { it.id() == "panel" } < movedOut.indexOfFirst { it.id() == "rotated-group" })
        val afterMoveOutPanel = draft.geometry("panel", EditorProtocol.TAB_FRONTEND)
        val afterMoveOutText = draft.geometry(text, EditorProtocol.TAB_FRONTEND)
        assertEquals(beforePanel.x, afterMoveOutPanel.x, 1e-6)
        assertEquals(beforePanel.y, afterMoveOutPanel.y, 1e-6)
        assertEquals(beforePanel.width, afterMoveOutPanel.width, 1e-6)
        assertEquals(beforePanel.height, afterMoveOutPanel.height, 1e-6)
        assertEquals(beforeText.x, afterMoveOutText.x, 1e-6)
        assertEquals(beforeText.y, afterMoveOutText.y, 1e-6)
        assertEquals(beforeText.width, afterMoveOutText.width, 1e-6)
        assertEquals(beforeText.height, afterMoveOutText.height, 1e-6)

        draft.undo(9)
        val restoredIntoTarget = draft.snapshot("26.1.2").frontend()
        assertEquals(target, restoredIntoTarget.first { it.id() == "panel" }.parentId())
        assertEquals(target, restoredIntoTarget.first { it.id() == text }.parentId())
        draft.undo(10)
        val restored = draft.snapshot("26.1.2").frontend()
        assertEquals("rotated-group", restored.first { it.id() == "panel" }.parentId())
        assertEquals("", restored.first { it.id() == text }.parentId())
    }

    @Test
    fun `group cannot be moved into its own descendant`() {
        val draft = draft()
        val descendant = draft.create(0, EditorProtocol.TAB_FRONTEND, EditorProtocol.KIND_GROUP, "rotated-group")
        assertThrows(IllegalArgumentException::class.java) {
            draft.reparent(1, listOf("rotated-group", "panel"), descendant, "")
        }
        assertEquals(1, draft.revision)
    }

    @Test
    fun `numeric property drag samples form one undo transaction`() {
        val draft = draft()
        draft.setProperty(0, EditorProtocol.TAB_FRONTEND, "panel", "offset.x", "6", 71, false)
        draft.setProperty(1, EditorProtocol.TAB_FRONTEND, "panel", "offset.x", "8", 71, false)
        draft.setProperty(2, EditorProtocol.TAB_FRONTEND, "panel", "offset.x", "8", 71, true)
        assertEquals("8", draft.snapshot("26.1.2").frontend().first { it.id() == "panel" }
            .properties().first { it.key() == "offset.x" }.value())
        draft.undo(2)
        assertEquals("5", draft.snapshot("26.1.2").frontend().first { it.id() == "panel" }
            .properties().first { it.key() == "offset.x" }.value())
    }
}
