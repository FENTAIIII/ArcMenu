package com.fentai.arcmenu.paper.editor

import com.fentai.arcmenu.core.config.TemplateCatalog
import com.fentai.arcmenu.core.config.TemplateLoadResult
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class TemplateFileOperationsTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `delete removes source file and refreshes catalog`() {
        val file = copyTemplate("saved-card.yaml")
        val catalog = TemplateCatalog()
        assertTrue(catalog.reload(directory).applied)

        TemplateFileOperations.delete(directory, catalog.templates.getValue("card")) { catalog.reload(directory) }

        assertFalse(Files.exists(file))
        assertTrue(catalog.templates.isEmpty())
    }

    @Test
    fun `delete restores source file when catalog refresh fails`() {
        val file = copyTemplate("saved-card.yml")
        val catalog = TemplateCatalog()
        assertTrue(catalog.reload(directory).applied)

        assertThrows(IllegalArgumentException::class.java) {
            TemplateFileOperations.delete(directory, catalog.templates.getValue("card")) {
                TemplateLoadResult(false, catalog.templates.size, listOf("synthetic failure"))
            }
        }

        assertTrue(Files.isRegularFile(file))
    }

    private fun copyTemplate(name: String): Path {
        val source = checkNotNull(javaClass.getResourceAsStream("/templates/card.yml"))
            .bufferedReader().use { it.readText() }
        return directory.resolve(name).also { Files.writeString(it, source) }
    }
}
