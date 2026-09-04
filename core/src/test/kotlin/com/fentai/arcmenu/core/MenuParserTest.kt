package com.fentai.arcmenu.core

import com.fentai.arcmenu.core.config.MenuFormatException
import com.fentai.arcmenu.core.config.MenuParser
import com.fentai.arcmenu.core.config.MenuEntrypointCompiler
import com.fentai.arcmenu.core.geometry.HitTester
import com.fentai.arcmenu.core.model.MenuPoint
import com.fentai.arcmenu.core.render.SceneCompiler
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class MenuParserTest {
    private val parser = MenuParser()
    private val example = """
        schema-version: 1
        id: example
        open-commands: [menu, profile]
        main-menu: true
        frontend:
          card:
            type: group
            offset: {x: 50, y: 20}
            rotation: {z: 90}
            scale: {x: 2, y: 2}
            children:
              decoration:
                type: rectangle
                offset: {x: 10}
                width: 30
                height: 10
        backend:
          button:
            x: 0
            y: 0
            width: 20
            height: 10
    """.trimIndent()

    @Test fun `frontend parent transform never changes backend hit region`() {
        val definition = parser.parse(example).definition
        assertEquals(listOf("menu", "profile"), definition.openCommands)
        assertTrue(definition.mainMenu)
        val visual = SceneCompiler().compile(definition.frontend).single()
        val center = visual.transform.transformPosition(Vector3d())
        assertEquals(50.0, center.x, 1e-8)
        assertEquals(40.0, center.y, 1e-8)
        val hit = HitTester(definition.backend)
        assertEquals("button", hit.hit(MenuPoint(9.0, 4.0))?.id)
        assertNull(hit.hit(MenuPoint(center.x, center.y)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["group: card", "parent: card", "children: {}", "rotation: {z: 15}", "scale: {x: 2}", "color: '#FF0000'", "z: 1"])
    fun `backend rejects grouping and visual attributes`(field: String) {
        val error = assertThrows<MenuFormatException> { parser.parse("$example\n    $field", "test.yml") }
        assertTrue(error.message!!.contains("test.yml.backend.button"))
    }

    @Test fun `ids are unique across frontend descendants and backend`() {
        val error = assertThrows<MenuFormatException> { parser.parse(example.replace("button:", "decoration:")) }
        assertTrue(error.message!!.contains("重复元素 ID: decoration"))
    }

    @Test fun `duplicate yaml keys are rejected rather than taking last value`() {
        assertThrows<MenuFormatException> { parser.parse(example + "\nid: overwritten") }
    }

    @Test fun `frontend spelling is canonical`() {
        val error = assertThrows<MenuFormatException> { parser.parse(example.replace("frontend:", "fontend:")) }
        assertTrue(error.message!!.contains("fontend"))
    }

    @Test fun `menu entrypoints use one strict syntax and one main menu`() {
        assertThrows<MenuFormatException> { parser.parse(example.replace("[menu, profile]", "[/menu]")) }
        assertThrows<MenuFormatException> { parser.parse(example.replace("[menu, profile]", "[menu, menu]")) }

        val main = parser.parse(example, "main.yml")
        val other = parser.parse(
            example.replace("id: example", "id: other")
                .replace("open-commands: [menu, profile]", "open-commands: [shop]")
                .replace("main-menu: true", "main-menu: false"),
            "other.yml",
        )
        val compiled = MenuEntrypointCompiler.compile(mapOf("example" to main, "other" to other))
        assertEquals("example", compiled.mainMenuId)
        assertEquals(mapOf("menu" to "example", "profile" to "example", "shop" to "other"), compiled.commands)

        assertThrows<MenuFormatException> {
            MenuEntrypointCompiler.compile(mapOf("other" to other))
        }
        assertThrows<MenuFormatException> {
            MenuEntrypointCompiler.compile(mapOf("example" to main, "other" to other.copy(
                definition = other.definition.copy(mainMenu = true),
            )))
        }
        assertThrows<MenuFormatException> {
            MenuEntrypointCompiler.compile(mapOf("example" to main, "other" to other.copy(
                definition = other.definition.copy(openCommands = listOf("menu")),
            )))
        }
    }

    @Test fun `groups reject opacity and finite geometry is required`() {
        assertThrows<MenuFormatException> { parser.parse(example.replace("type: group", "type: group\n    opacity: 100")) }
        assertThrows<MenuFormatException> { parser.parse(example.replace("width: 20", "width: .nan")) }
        assertThrows<MenuFormatException> { parser.parse(example.replace("height: 10", "height: -10")) }
    }

    @Test fun `image accepts virtual png path and rejects traversal or duplicate source aliases`() {
        val image = example.replace(
            "type: rectangle\n        offset: {x: 10}\n        width: 30\n        height: 10",
            "type: image\n        source: /ui/logo.png\n        width: 30\n        height: 10\n        update: 20",
        )
        val node = parser.parse(image).definition.frontend.single().let { it as com.fentai.arcmenu.core.model.GroupNode }
            .children.single() as com.fentai.arcmenu.core.model.ImageNode
        assertEquals("/ui/logo.png", node.source)
        assertEquals(20, node.updateTicks)
        assertThrows<MenuFormatException> { parser.parse(image.replace("/ui/logo.png", "/../logo.png")) }
        assertThrows<MenuFormatException> { parser.parse(image.replace("source: /ui/logo.png", "source: /ui/logo.png\n        path: /ui/other.png")) }
    }

    @Test fun `preview parsing retains original expressions without rewriting source`() {
        val source = example + "\n    actions:\n      all:\n        - 'console: example_command'\n"
        val document = parser.parse(source)
        assertEquals(source, document.source)
        assertEquals("button", document.definition.backend.single().id)
    }
}
