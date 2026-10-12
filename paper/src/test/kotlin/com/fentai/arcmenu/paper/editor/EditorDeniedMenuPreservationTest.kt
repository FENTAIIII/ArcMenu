package com.fentai.arcmenu.paper.editor

import com.fentai.arcmenu.core.config.MenuParser
import com.fentai.arcmenu.protocol.EditorProtocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class EditorDeniedMenuPreservationTest {
    @TempDir lateinit var directory: Path

    @Test fun `permission denied target survives scalar and structural editor saves`() {
        val source = """
            schema-version: 1
            id: protected-menu
            permission: menus.protected
            permission-denied-menu: access-denied
            frontend:
              panel: {type: rectangle, width: 40, height: 20}
            backend: {}
        """.trimIndent() + "\n"
        val file = directory.resolve("protected-menu.yml")
        Files.writeString(file, source)
        val parser = MenuParser()
        val draft = EditorDraft(UUID.randomUUID(), file, "protected-menu", source,
            parser.parse(source).definition, {}, { parser.parse(it).definition }, directory.resolve("backup.yml"))

        draft.setProperty(draft.revision, EditorProtocol.TAB_FRONTEND, "panel", "width", "60")
        draft.save(draft.revision)
        assertEquals("access-denied", parser.parse(Files.readString(file)).definition.permissionDeniedMenu)

        draft.create(draft.revision, EditorProtocol.TAB_FRONTEND, EditorProtocol.KIND_RECTANGLE, "")
        draft.save(draft.revision)
        assertEquals("access-denied", parser.parse(Files.readString(file)).definition.permissionDeniedMenu)
    }
}
