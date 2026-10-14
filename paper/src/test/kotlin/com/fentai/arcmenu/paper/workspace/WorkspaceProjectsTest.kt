package com.fentai.arcmenu.paper.workspace

import com.fentai.arcmenu.core.config.MenuCatalog
import com.fentai.arcmenu.core.config.TemplateCatalog
import com.fentai.arcmenu.core.config.templateScene
import com.fentai.arcmenu.core.model.GroupNode
import com.fentai.arcmenu.core.model.VisualNode
import com.fentai.arcmenu.paper.localization.LanguageManager
import com.fentai.arcmenu.paper.localization.failureText
import com.google.gson.JsonParser
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

class WorkspaceProjectsTest {
    @TempDir lateinit var root: Path
    private fun args(text: String) = JsonParser.parseString(text).asJsonObject
    private fun store(busy: Boolean = false, failRefresh: () -> Boolean = { false }, includeTemplates: Boolean = false): WorkspaceProjects {
        Files.createDirectories(root.resolve("menus"))
        val catalog = MenuCatalog()
        val templates = TemplateCatalog()
        fun reload() {
            val result = catalog.reload(root.resolve("menus")); require(result.applied) { result.errors.joinToString() }
            val templateResult = templates.reload(root.resolve("templates")); require(templateResult.applied) { templateResult.errors.joinToString() }
            require(!failRefresh()) { "Validation rejected" }
        }
        reload()
        return WorkspaceProjects(root, { catalog.documents }, ::reload, { busy }, { templates.templates })
    }
    private fun templateSource(id: String): String = """
        schema-version: 1
        id: $id
        frontend:
          card-root:
            type: group
            offset: {x: 280, y: -170}
            children:
              backdrop:
                type: rectangle
                width: 180
                height: 90
                color: '#304050'
              details:
                type: group
                children:
                  label:
                    type: text
                    content: 'Template preview'
    """.trimIndent() + "\n"
    private fun writeTemplate(id: String, path: String = "nested/cards/$id.yml"): Path = root.resolve("templates").resolve(path).also {
        Files.createDirectories(it.parent); Files.writeString(it, templateSource(id))
    }
    @Test fun `open writer owns saved settings and files`() {
        store().create(args("""{"type":"menu","id":"a"}"""))
        val store = store(busy = true)
        assertThrows(IllegalArgumentException::class.java) { store.configure(args("""{"type":"menu","id":"a","width":640}""")) }
        assertThrows(IllegalArgumentException::class.java) { store.delete(listOf(WorkspaceSelection("menu", "a"))) }
    }
    @Test fun `canvas depth mode survives project settings changes and rejects unsupported values`() {
        val store = store()
        store.create(args("""{"type":"menu","id":"layers","depthMode":"layered"}"""))
        assertEquals("layered", store.list().single { it["id"] == "layers" }["depthMode"])
        store.configure(args("""{"type":"menu","id":"layers","width":640}"""))
        val file = root.resolve("menus/layers.yml")
        assertTrue(Files.readString(file).contains("depth-mode: layered"))
        val previous = Files.readString(file)
        assertThrows(IllegalArgumentException::class.java) {
            store.configure(args("""{"type":"menu","id":"layers","depthMode":"invalid"}"""))
        }
        assertEquals(previous, Files.readString(file))
        store.configure(args("""{"type":"menu","id":"layers","depthMode":"perspective"}"""))
        assertEquals("perspective", store.list().single { it["id"] == "layers" }["depthMode"])
    }
    @Test fun `project validation selects player language while preserving English diagnostics`() {
        val store = store()
        store.create(args("""{"type":"menu","id":"shop"}"""))
        val error = assertThrows(IllegalArgumentException::class.java) {
            store.create(args("""{"type":"menu","id":"shop","folder":"other"}"""))
        }
        fun rendered(locale: String): String {
            val catalog = requireNotNull(javaClass.getResourceAsStream("/languages/$locale.yml"))
                .bufferedReader(Charsets.UTF_8).use(YamlConfiguration::loadConfiguration)
            return failureText(error, "Fallback") { key, arguments ->
                LanguageManager.format(requireNotNull(catalog.getString(key)), arguments)
            }
        }
        assertEquals("Project ID already exists: shop", error.message)
        assertEquals(error.message, rendered("en_US"))
        assertEquals("项目 ID 已存在：shop", rendered("zh_CN"))
        assertEquals("Project ID already exists: shop", error.message)
        assertFalse(Files.exists(root.resolve("menus/other/shop.yml")))
    }

    @Test fun `template viewing reads saved visuals without menu settings or author file changes`() {
        val file = writeTemplate("card")
        val store = store(includeTemplates = true)
        val saved = Files.readString(file)
        val preview = store.previewScene(WorkspaceSelection("template", "card"))
        assertEquals("card", preview.id)
        assertTrue(preview.backend.isEmpty()); assertTrue(preview.openCommands.isEmpty())
        assertEquals("", preview.permission); assertFalse(preview.mainMenu)
        fun authored(node: VisualNode): VisualNode? = if (node.properties.id == "card-root") node
            else if (node is GroupNode) node.children.firstNotNullOfOrNull(::authored) else null
        val group = preview.frontend.firstNotNullOfOrNull(::authored) as GroupNode
        assertEquals(280.0, group.properties.transform.offset.x)
        assertEquals(-170.0, group.properties.transform.offset.y)
        assertThrows(IllegalArgumentException::class.java) { store.configure(args("""{"type":"template","id":"card","width":640}""")) }
        assertThrows(IllegalArgumentException::class.java) { store.create(args("""{"type":"template","id":"new_card"}""")) }
        assertEquals(saved, Files.readString(file))
        assertFalse(Files.exists(root.resolve("templates/new_card.yml")))
        Files.writeString(file, saved.replace("id: card", "id: changed"))
        assertThrows(IllegalArgumentException::class.java) { store.previewScene(WorkspaceSelection("template", "card")) }
    }

    @Test fun `saved template preview fits the native size of an image with omitted dimensions`() {
        val file = root.resolve("templates/nested/image.yml")
        Files.createDirectories(file.parent)
        val source = "schema-version: 1\nid: image\nfrontend:\n  card:\n    type: group\n    children:\n      artwork:\n        type: image\n        source: /nested/art.png\n"
        Files.writeString(file, source)
        val image = root.resolve("images/nested/art.png")
        Files.createDirectories(image.parent)
        ImageIO.write(BufferedImage(640, 360, BufferedImage.TYPE_INT_ARGB), "png", image.toFile())
        val store = store(includeTemplates = true)
        val actual = store.previewScene(WorkspaceSelection("template", "image"))
        val expected = templateScene(TemplateCatalog().parse(source, "nested/image.yml")) { 640.0 to 360.0 }
        assertEquals(expected, actual)
        assertNotEquals(templateScene(TemplateCatalog().parse(source, "nested/image.yml")), actual)
        assertEquals(source, Files.readString(file))
    }

    @Test fun `template source names cannot target a different project directory`() {
        Files.createDirectories(root.resolve("menus")); Files.createDirectories(root.resolve("templates"))
        val protected = root.resolve("menus/card.yml"); Files.writeString(protected, templateSource("card"))
        val template = TemplateCatalog().parse(templateSource("card"), "../menus/card.yml")
        val store = WorkspaceProjects(root, { emptyMap() }, {}, { false }, { mapOf("card" to template) })
        assertThrows(IllegalArgumentException::class.java) { store.sceneFile(WorkspaceSelection("template", "card")) }
        assertThrows(IllegalArgumentException::class.java) { store.delete(listOf(WorkspaceSelection("template", "card"))) }
        assertTrue(Files.exists(protected))
    }
}
