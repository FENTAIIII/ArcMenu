package com.fentai.arcmenu.paper

import com.fentai.arcmenu.core.config.MenuParser
import com.fentai.arcmenu.core.config.AnimationCatalog
import com.fentai.arcmenu.core.config.MenuSetValidator
import com.fentai.arcmenu.core.behavior.*
import com.fentai.arcmenu.core.model.*
import com.fentai.arcmenu.core.render.SceneCompiler
import com.fentai.arcmenu.core.config.TemplateCatalog
import com.fentai.arcmenu.paper.input.PointerSettingsLoader
import com.fentai.arcmenu.paper.render.TooltipStyleLoader
import com.fentai.arcmenu.paper.runtime.MenuShortcutSettingsLoader
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.StringReader

class BundledMenuTest {
    @Test fun `shipped example validates and demonstrates independent backend regions`() {
        val source = requireNotNull(javaClass.getResourceAsStream("/menus/example.yml")).bufferedReader().use { it.readText() }
        val menu = MenuParser().parse(source).definition
        assertEquals("example", menu.id)
        assertEquals(listOf("arc"), menu.openCommands)
        assertTrue(menu.mainMenu)
        assertTrue(menu.frontend.any { it is GroupNode })
        val flattened = buildList {
            fun visit(node: VisualNode) {
                add(node)
                if (node is GroupNode) node.children.forEach(::visit)
            }
            menu.frontend.forEach(::visit)
        }
        assertTrue(flattened.any { it is RectangleNode })
        assertTrue(flattened.any { it is FrameNode })
        assertTrue(flattened.any { it is TextNode })
        assertTrue(flattened.any { it is ItemNode })
        assertTrue(flattened.any { it is BlockNode })
        assertTrue(flattened.any { it.properties.id == "divider-line" })
        assertEquals(42.7, menu.canvas.pixelsPerBlock, 1e-9)
        assertEquals(0.427, (menu.frontend.single() as GroupNode).properties.transform.scaleX, 1e-9)
        assertEquals(2, menu.backend.size)
        assertEquals(12, SceneCompiler().compile(menu.frontend).size)
        assertEquals(20, flattened.filterIsInstance<TextNode>().first { it.properties.id == "title" }.updateTicks)
        assertTrue(menu.backend.first().actions.matching(ClickInput.RIGHT).size >= 2)
        assertTrue(menu.backend.last().actions.allActions().any { it is OpenMenuAction })
    }

    @Test fun `shipped route target parses with back action`() {
        val source = requireNotNull(javaClass.getResourceAsStream("/menus/details.yml")).bufferedReader().use { it.readText() }
        val menu = MenuParser().parse(source).definition
        assertEquals("details", menu.id)
        assertTrue(menu.backend.single().actions.allActions().any { it is BackAction })
    }

    @Test fun `shipped M4 resource probe contains generated image`() {
        val source = requireNotNull(javaClass.getResourceAsStream("/menus/m4-resources.yml")).bufferedReader().use { it.readText() }
        val menu = MenuParser().parse(source).definition
        assertEquals("m4-resources", menu.id)
        val root = menu.frontend.single() as GroupNode
        assertTrue(root.children.any { it is ImageNode && it.source == "/example.png" })
    }

    @Test fun `shipped M5 menu and animation catalog bind transitions tracks and public route`() {
        fun document(path: String) = requireNotNull(javaClass.getResourceAsStream(path)).bufferedReader().use {
            MenuParser().parse(it.readText(), path)
        }
        val animated = document("/menus/m5-animation.yml")
        val details = document("/menus/details.yml")
        val example = document("/menus/example.yml")
        MenuSetValidator.validate(mapOf("m5-animation" to animated, "details" to details, "example" to example))
        val animationSource = requireNotNull(javaClass.getResourceAsStream("/animations.yml")).bufferedReader().use { it.readText() }
        val configuration = AnimationCatalog().parse(animationSource, mapOf("m5-animation" to animated, "details" to details))

        val binding = requireNotNull(configuration.forMenu("m5-animation"))
        assertEquals(5, binding.tracks.size)
        assertNotNull(binding.transition?.enter)
        assertTrue(animated.definition.backend.flatMap { it.actions.allActions().toList() }.any { it is PlayAnimationAction })
        assertTrue(animated.definition.backend.flatMap { it.actions.allActions().toList() }.any { it is OpenMenuAction && ':' in it.menuId })
    }

    @Test fun `release defaults contain validated configs templates and referenced images`() {
        fun resource(path: String): String = requireNotNull(javaClass.getResourceAsStream(path)) {
            "missing bundled resource $path"
        }.bufferedReader().use { it.readText() }

        val config = YamlConfiguration.loadConfiguration(StringReader(resource("/config.yml")))
        val pointer = PointerSettingsLoader.from(config)
        assertEquals(5.0, pointer.cursorStyle.size, 1e-9)
        assertTrue(MenuShortcutSettingsLoader.from(config).shiftF)

        val tooltips = TooltipStyleLoader.from(
            YamlConfiguration.loadConfiguration(StringReader(resource("/tooltip.yml"))),
        )
        assertEquals(2.989, tooltips.touch.size, 1e-9)
        assertEquals(4.375, tooltips.mouse.size, 1e-9)
        assertNotNull(tooltips.touch.skin)
        assertNotNull(tooltips.mouse.skin)

        val template = TemplateCatalog().parse(resource("/templates/card.yml"), "card.yml")
        assertEquals("card", template.id)

        listOf(
            "/images/example.png",
            "/images/mouse/mouse.png",
            "/images/mouse/choose.png",
            "/images/ce/topaz_background.png",
            "/images/ce/topaz_frame.png",
        ).forEach { path ->
            requireNotNull(javaClass.getResourceAsStream(path)) { "missing bundled image $path" }.use {
                assertTrue(it.read() >= 0, "$path is empty")
            }
        }
    }
}
