package com.fentai.arcmenu.paper.render

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TooltipStyleTest {
    @Test
    fun `legacy single-section configuration also controls cursor tooltip`() {
        val yaml = YamlConfiguration().apply {
            set("crosshair.offset.x", 50)
            set("crosshair.offset.y", -50)
            set("crosshair.offset.z", 3)
            set("crosshair.size", 7)
            set("crosshair.line-width", 180)
            set("crosshair.background", "#D0101010")
        }

        val styles = TooltipStyleLoader.from(yaml)

        assertEquals(styles.touch, styles.mouse)
        assertEquals(50.0, styles.mouse.offsetX)
        assertEquals(-50.0, styles.mouse.offsetY)
    }

    @Test
    fun `explicit cursor section remains independently configurable`() {
        val yaml = YamlConfiguration().apply {
            set("crosshair.offset.x", 50)
            set("cursor.offset.x", 8)
        }

        val styles = TooltipStyleLoader.from(yaml)

        assertNotEquals(styles.touch, styles.mouse)
        assertEquals(50.0, styles.touch.offsetX)
        assertEquals(8.0, styles.mouse.offsetX)
    }

    @Test
    fun `resource skin is optional and each pointer mode can configure one`() {
        val plain = TooltipStyleLoader.from(YamlConfiguration())
        assertNull(plain.touch.skin)
        assertNull(plain.mouse.skin)

        val yaml = YamlConfiguration().apply {
            set("cursor.skin.background", "/ce/topaz_background.png")
            set("cursor.skin.frame", "/ce/topaz_frame.png")
            set("cursor.skin.border", 8)
            set("cursor.skin.padding.left", 10)
            set("cursor.skin.padding.right", 11)
            set("cursor.skin.padding.top", 7)
            set("cursor.skin.padding.bottom", 6)
            set("cursor.skin.min-size.width", 30)
            set("cursor.skin.min-size.height", 25)
            set("cursor.skin.size-adjust.width", 3)
            set("cursor.skin.size-adjust.height", 4)
            set("cursor.skin.offset.x", 1.5)
            set("cursor.skin.offset.y", -2.5)
            set("cursor.skin.offset.z", -0.4)
            set("cursor.skin.scale.x", 1.1)
            set("cursor.skin.scale.y", 0.9)
            set("cursor.skin.text-offset.x", 2.0)
            set("cursor.skin.text-offset.y", 3.0)
            set("cursor.skin.text-offset.z", 0.1)
            set("cursor.skin.seam-overlap.x", 0.25)
            set("cursor.skin.seam-overlap.y", 0.5)
            set("cursor.skin.glyph-offset.x", -0.2)
            set("cursor.skin.glyph-offset.y", 0.3)
            set("cursor.skin.column-offset.left", -1.0)
            set("cursor.skin.column-offset.center", 0.0)
            set("cursor.skin.column-offset.right", 1.0)
            set("cursor.skin.row-offset.top", 1.0)
            set("cursor.skin.row-offset.center", 0.0)
            set("cursor.skin.row-offset.bottom", -1.0)
        }
        val styles = TooltipStyleLoader.from(yaml)
        val skin = requireNotNull(styles.mouse.skin)
        assertEquals("/ce/topaz_background.png", skin.background)
        assertEquals("/ce/topaz_frame.png", skin.frame)
        assertEquals(8, skin.border)
        assertEquals(TooltipInsets(10, 11, 7, 6), skin.padding)
        assertEquals(3, skin.widthAdjust)
        assertEquals(4, skin.heightAdjust)
        assertEquals(1.5, skin.offsetX)
        assertEquals(-2.5, skin.offsetY)
        assertEquals(-0.4, skin.offsetZ)
        assertEquals(1.1, skin.scaleX)
        assertEquals(0.9, skin.scaleY)
        assertEquals(2.0, skin.textOffsetX)
        assertEquals(Triple(-1.0, 0.0, 1.0), skin.columnOffsets)
        assertEquals(Triple(1.0, 0.0, -1.0), skin.rowOffsets)
        assertEquals(setOf(skin.request), styles.nineSliceRequests())
    }

    @Test
    fun `tooltip skin box grows with resolved content and wrapping`() {
        val skin = TooltipSkin("/skin/background.png", null, 2, TooltipInsets(2, 2, 3, 3), 5, 7)

        val short = TooltipMeasurer.box(listOf("&6abc"), 100, skin)
        val wrapped = TooltipMeasurer.box(listOf("abcdefghijkl"), 12, skin)

        assertEquals(15, short.heightPixels)
        assertEquals(65, wrapped.heightPixels)
        assertNotEquals(short.widthPixels, wrapped.widthPixels)
    }

    @Test
    fun `skin layout surrounds the centered text block instead of starting at its origin`() {
        val skin = TooltipSkin("/skin/background.png", null, 2, TooltipInsets(2, 2, 3, 3), 5, 7)
        val style = TooltipStyle(0.0, 0.0, 3.0, 9.0, 100, 0, skin)
        val box = TooltipMeasurer.box(listOf("abc"), 100, skin)

        val layout = TooltipSkinLayout.layout(100.0, 50.0, style, skin, box)
        val cells = layout.cells
        val left = cells.getValue(com.fentai.arcmenu.paper.resource.NineSlicePart.LEFT)
        val right = cells.getValue(com.fentai.arcmenu.paper.resource.NineSlicePart.RIGHT)
        val top = cells.getValue(com.fentai.arcmenu.paper.resource.NineSlicePart.TOP)
        val bottom = cells.getValue(com.fentai.arcmenu.paper.resource.NineSlicePart.BOTTOM)

        assertTrue(left.centerX > 100.0)
        assertTrue(right.centerX > 100.0)
        assertTrue(top.centerY > 50.0)
        assertTrue(bottom.centerY > 50.0)
        assertTrue(layout.textOriginX > 100.0)
        assertTrue(layout.textOriginY > 50.0)
    }

    @Test
    fun `selected corner remains fixed when tooltip content width changes`() {
        val skin = TooltipSkin("/skin/background.png", null, 2, TooltipInsets(2, 2, 2, 2), 5, 5)
        TooltipAnchor.entries.forEach { anchor ->
            val style = TooltipStyle(0.0, 0.0, 3.0, 9.0, 100, 0, skin, anchor)
            listOf("short", "a much wider tooltip line").forEach { content ->
                val box = TooltipMeasurer.box(listOf(content), style.effectiveLineWidth, skin)
                val cells = TooltipSkinLayout.layout(12.0, 34.0, style, skin, box).cells.values
                val left = cells.minOf { it.centerX - it.width / 2.0 }
                val right = cells.maxOf { it.centerX + it.width / 2.0 }
                val top = cells.maxOf { it.centerY + it.height / 2.0 }
                val bottom = cells.minOf { it.centerY - it.height / 2.0 }
                when (anchor) {
                    TooltipAnchor.TOP_LEFT -> { assertEquals(12.0, left); assertEquals(34.0, top) }
                    TooltipAnchor.TOP_RIGHT -> { assertEquals(12.0, right); assertEquals(34.0, top) }
                    TooltipAnchor.BOTTOM_LEFT -> { assertEquals(12.0, left); assertEquals(34.0, bottom) }
                    TooltipAnchor.BOTTOM_RIGHT -> { assertEquals(12.0, right); assertEquals(34.0, bottom) }
                }
            }
        }
    }

    @Test
    fun `tooltip list entries do not wrap unless wrap is explicitly enabled`() {
        val long = listOf("&fIndependent axis-aligned rectangle")
        val short = listOf("&fIndependent axis-aligned rectangl")
        val noWrap = TooltipStyle(0.0, 0.0, 3.0, 7.0, 180, 0)
        val wrap = noWrap.copy(wrap = true)

        assertEquals(9, TooltipMeasurer.content(long, noWrap.effectiveLineWidth).heightPixels)
        assertEquals(9, TooltipMeasurer.content(short, noWrap.effectiveLineWidth).heightPixels)
        assertEquals(19, TooltipMeasurer.content(long, wrap.effectiveLineWidth).heightPixels)
        assertEquals(9, TooltipMeasurer.content(short, wrap.effectiveLineWidth).heightPixels)
    }

    @Test
    fun `tooltip skin rejects a minimum smaller than its border`() {
        val yaml = YamlConfiguration().apply {
            set("crosshair.skin.background", "/skin/background.png")
            set("crosshair.skin.border", 8)
            set("crosshair.skin.min-size.width", 16)
        }

        assertThrows<IllegalArgumentException> { TooltipStyleLoader.from(yaml) }
    }

    @Test
    fun `canonical touch and mouse sections take precedence over legacy names`() {
        val yaml = YamlConfiguration().apply {
            set("crosshair.offset.x", 1)
            set("cursor.offset.x", 2)
            set("touch.offset.x", 3)
            set("touch.anchor", "top-right")
            set("touch.wrap", true)
            set("mouse.offset.x", 4)
        }

        val styles = TooltipStyleLoader.from(yaml)

        assertEquals(3.0, styles.touch.offsetX)
        assertEquals(4.0, styles.mouse.offsetX)
        assertEquals(TooltipAnchor.TOP_RIGHT, styles.touch.anchor)
        assertTrue(styles.touch.wrap)
    }
}
