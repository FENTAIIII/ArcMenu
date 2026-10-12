package com.fentai.arcmenu.paper.workspace

import com.fentai.arcmenu.core.config.MenuParser
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class WorkspaceDeniedMenuPreservationTest {
    @TempDir lateinit var directory: Path

    @Test fun `global canvas configuration preserves the permission denied target`() {
        val file = directory.resolve("menus/protected.yml")
        Files.createDirectories(file.parent)
        Files.writeString(file, """
            schema-version: 1
            id: protected-menu
            permission: menus.protected
            permission-denied-menu: access-denied
            frontend: {}
            backend: {}
        """.trimIndent() + "\n")
        val parser = MenuParser()
        fun documents() = mapOf("protected-menu" to parser.parse(Files.readString(file), "protected.yml"))
        val projects = WorkspaceProjects(directory, ::documents, {}, { false })

        projects.configure(JsonParser.parseString("""{"type":"menu","id":"protected-menu","width":640}""").asJsonObject)

        val definition = parser.parse(Files.readString(file)).definition
        assertEquals(640.0, definition.canvas.width)
        assertEquals("access-denied", definition.permissionDeniedMenu)
    }
}
