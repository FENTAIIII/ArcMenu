package com.fentai.arcmenu.paper.runtime

import com.fentai.arcmenu.core.model.Canvas
import com.fentai.arcmenu.paper.input.PointerMode
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RuntimeScreenSettingsTest {
    @Test
    fun `runtime screen moves near eye while preserving apparent size`() {
        val authored = Canvas(width = 320.0, height = 180.0, pixelsPerBlock = 100.0, distance = 3.0)
        val runtime = authored.forRuntimeScreen(ScreenPlacement(0.65))

        assertEquals(0.65, runtime.distance, 1e-12)
        assertEquals(461.53846153846155, runtime.pixelsPerBlock, 1e-12)
        assertEquals(
            authored.width / authored.pixelsPerBlock / authored.distance,
            runtime.width / runtime.pixelsPerBlock / runtime.distance,
            1e-12,
        )
        assertEquals(0.6933333333333334, runtime.width / runtime.pixelsPerBlock, 1e-12)
    }

    @Test
    fun `offset configuration defaults both modes to CRServer near-eye distance`() {
        val settings = RuntimeScreenSettingsLoader.from(YamlConfiguration())
        assertEquals(0.65, settings.forMode(PointerMode.TOUCH).distance, 1e-12)
        assertEquals(0.65, settings.forMode(PointerMode.MOUSE).distance, 1e-12)
    }

    @Test
    fun `offset configuration separates modes and reads full translation`() {
        val yaml = YamlConfiguration().apply {
            set("touch.distance", 0.55)
            set("mouse.distance", 0.8)
            set("mouse.offset.x", 0.1)
            set("mouse.offset.y", -0.2)
            set("mouse.offset.z", 0.03)
        }
        val settings = RuntimeScreenSettingsLoader.from(yaml)

        assertEquals(0.55, settings.touch.distance, 1e-12)
        assertEquals(0.8, settings.mouse.distance, 1e-12)
        assertEquals(0.1, settings.mouse.offset.x, 1e-12)
        assertEquals(-0.2, settings.mouse.offset.y, 1e-12)
        assertEquals(0.03, settings.mouse.offset.z, 1e-12)
    }

    @Test
    fun `offset configuration rejects unsafe and unknown values`() {
        val tooClose = YamlConfiguration().apply { set("mouse.distance", 0.01) }
        assertThrows(IllegalArgumentException::class.java) { RuntimeScreenSettingsLoader.from(tooClose) }

        val unknown = YamlConfiguration().apply { set("touch.scale", 0.2) }
        assertThrows(IllegalArgumentException::class.java) { RuntimeScreenSettingsLoader.from(unknown) }
    }
}
