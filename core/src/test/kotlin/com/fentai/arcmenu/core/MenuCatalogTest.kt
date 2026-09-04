package com.fentai.arcmenu.core

import com.fentai.arcmenu.core.config.MenuCatalog
import com.fentai.arcmenu.core.config.MenuSetValidator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class MenuCatalogTest {
    @TempDir lateinit var directory: Path
    private val valid = "schema-version: 1\nid: example\nfrontend: {}\nbackend: {}\n"

    @Test fun `failed reload preserves old catalog even if another file changed successfully`() {
        Files.writeString(directory.resolve("one.yml"), valid)
        val catalog = MenuCatalog()
        assertTrue(catalog.reload(directory).applied)
        val previous = catalog.documents
        Files.writeString(directory.resolve("one.yml"), valid.replace("example", "replacement"))
        Files.writeString(directory.resolve("broken.yml"), "frontend: [")
        val result = catalog.reload(directory)
        assertFalse(result.applied)
        assertSame(previous, catalog.documents)
        assertEquals(setOf("example"), catalog.documents.keys)
    }

    @Test fun `duplicate menu ids abort the entire publication`() {
        Files.writeString(directory.resolve("one.yml"), valid)
        Files.writeString(directory.resolve("two.yml"), valid)
        val catalog = MenuCatalog()
        assertFalse(catalog.reload(directory).applied)
        assertTrue(catalog.documents.isEmpty())
    }

    @Test fun `renderer validation failure also preserves catalog`() {
        Files.writeString(directory.resolve("one.yml"), valid)
        val catalog = MenuCatalog()
        catalog.reload(directory)
        val previous = catalog.documents
        val result = catalog.reload(directory) { throw IllegalArgumentException("invalid material") }
        assertFalse(result.applied)
        assertSame(previous, catalog.documents)
        assertTrue(result.errors.single().contains("invalid material"))
    }

    @Test fun `valid deletion is reflected in next publication`() {
        val file = directory.resolve("one.yml")
        Files.writeString(file, valid)
        val catalog = MenuCatalog()
        catalog.reload(directory)
        Files.delete(file)
        assertTrue(catalog.reload(directory).applied)
        assertTrue(catalog.documents.isEmpty())
    }

    @Test fun `missing static open route aborts publication`() {
        Files.writeString(directory.resolve("one.yml"), valid.replace("backend: {}", "backend:\n  link:\n    width: 1\n    height: 1\n    actions: 'open: missing'"))
        val result = MenuCatalog().reload(directory, {}, MenuSetValidator::validate)
        assertFalse(result.applied)
        assertTrue(result.errors.single().contains("backend.link.actions"))
        assertTrue(result.errors.single().contains("missing"))
    }
}
