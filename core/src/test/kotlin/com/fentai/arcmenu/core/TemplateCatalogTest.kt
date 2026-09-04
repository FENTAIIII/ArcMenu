package com.fentai.arcmenu.core

import com.fentai.arcmenu.core.config.TemplateCatalog
import com.fentai.arcmenu.core.config.TemplateInstantiator
import com.fentai.arcmenu.core.model.GroupNode
import com.fentai.arcmenu.core.model.ImageNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class TemplateCatalogTest {
    @TempDir
    lateinit var directory: Path

    private val source = """
        schema-version: 1
        id: purchase-card
        frontend:
          card:
            type: group
            offset: {x: 4, y: 5}
            children:
              art:
                type: image
                source: /ui/card.png
                width: 80
                height: 40
              label:
                type: text
                content: '&fBuy'
                size: 8
    """.trimIndent()

    @Test
    fun `template creates detached group with new globally stable ids`() {
        val template = TemplateCatalog().parse(source)
        val instance = TemplateInstantiator.instantiate(template, "shop-card")
        assertEquals("shop-card", instance.properties.id)
        assertEquals(listOf("shop-card-art", "shop-card-label"), instance.children.map { it.properties.id })
        assertTrue(instance.children.first() is ImageNode)
        assertEquals("card", template.root.properties.id)
    }

    @Test
    fun `catalog reload is atomic when one template is invalid`() {
        Files.writeString(directory.resolve("valid.yml"), source)
        val catalog = TemplateCatalog()
        assertTrue(catalog.reload(directory).applied)
        assertEquals(setOf("purchase-card"), catalog.templates.keys)

        Files.writeString(directory.resolve("invalid.yml"), source.replace("type: group", "type: rectangle"))
        val failed = catalog.reload(directory)
        assertFalse(failed.applied)
        assertEquals(setOf("purchase-card"), catalog.templates.keys)
    }

    @Test
    fun `template forbids backend and multiple or non-group roots`() {
        val catalog = TemplateCatalog()
        assertThrows(IllegalArgumentException::class.java) { catalog.parse(source + "\nbackend: {}") }
        assertThrows(IllegalArgumentException::class.java) {
            catalog.parse(source.replace("frontend:", "frontend:\n  second:\n    type: group\n    children: {}"))
        }
        assertThrows(IllegalArgumentException::class.java) { catalog.parse(source.replace("type: group", "type: rectangle")) }
    }
}
