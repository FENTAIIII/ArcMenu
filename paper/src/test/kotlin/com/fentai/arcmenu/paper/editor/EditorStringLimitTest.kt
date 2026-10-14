package com.fentai.arcmenu.paper.editor

import com.fentai.arcmenu.core.config.MenuParser
import com.fentai.arcmenu.protocol.EditorProtocol
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class EditorStringLimitTest {
    @TempDir lateinit var directory: Path

    private fun draft(source: String): EditorDraft {
        val parser = MenuParser()
        val file = directory.resolve("long.yml")
        Files.writeString(file, source)
        return EditorDraft(UUID.randomUUID(), file, "long", source, parser.parse(source).definition,
            validate = {}, parseSaved = { parser.parse(it).definition }, backupFile = directory.resolve("backup.yml"))
    }

    private fun source(action: String = "tell: ready") = """
        schema-version: 1
        id: long
        canvas: {width: 320, height: 180}
        frontend: {}
        backend:
          buy:
            width: 20
            height: 20
            actions:
              right: '$action'
    """.trimIndent()

    @Test fun `file loaded large Chinese action can be edited and transported without data loss`() {
        val draft = draft(source("tell: " + "中文".repeat(13_000)))
        val original = draft.snapshot("26.3")
        assertEquals(original, EditorProtocol.decode(EditorProtocol.encode(original)))
        val action = "right: 'tell: " + "新中文".repeat(13_000) + "'"
        draft.setProperty(0, EditorProtocol.TAB_BACKEND, "buy", "actions", action)
        val snapshot = draft.snapshot("26.3")
        assertEquals(snapshot, EditorProtocol.decode(EditorProtocol.encode(snapshot)))
        assertTrue(snapshot.backend().single().properties().single { it.key() == "actions" }.value().contains("新中文"))
        draft.undo(draft.revision)
        assertEquals(original.backend(), draft.snapshot("26.3").backend())
    }

    @Test fun `oversized canonical property rejects without altering draft revision source or history`() {
        val draft = draft(source())
        val before = draft.snapshot("26.3")
        val beforeSource = draft.source
        val failure = assertThrows(EditorProtocol.StringLimitException::class.java) {
            draft.setProperty(0, EditorProtocol.TAB_BACKEND, "buy", "actions", "right: 'tell: " + "a".repeat(EditorProtocol.MAX_STRING_BYTES) + "'")
        }
        assertEquals("backend.buy.actions", failure.field())
        assertEquals(0, draft.revision)
        assertEquals(beforeSource, draft.source)
        assertEquals(before, draft.snapshot("26.3"))
        assertFalse(draft.dirty)
        draft.setProperty(0, EditorProtocol.TAB_BACKEND, "buy", "actions", "right: 'tell: accepted'")
        draft.undo(draft.revision)
        assertEquals(before.backend(), draft.snapshot("26.3").backend())
    }

    @Test fun `total snapshot overflow rejects otherwise valid individual property`() {
        val source = buildString {
            append("schema-version: 1\nid: long\ncanvas: {width: 320, height: 180}\nfrontend:\n")
            for (index in 0..4) {
                append("  text-$index:\n    type: text\n    content: '")
                append(if (index == 4) "ready" else "a".repeat(220_000))
                append("'\n")
            }
            append("backend: {}\n")
        }
        val draft = draft(source)
        val before = draft.snapshot("26.3")
        EditorProtocol.encode(before)
        assertThrows(EditorProtocol.PacketLimitException::class.java) {
            draft.setProperty(0, EditorProtocol.TAB_FRONTEND, "text-4", "content", "a".repeat(220_000))
        }
        assertEquals(0, draft.revision)
        assertEquals(before, draft.snapshot("26.3"))
    }
}
