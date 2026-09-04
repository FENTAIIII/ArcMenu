package com.fentai.arcmenu.paper.input

import com.fentai.arcmenu.core.model.Canvas
import com.fentai.arcmenu.core.model.MenuPoint
import com.fentai.arcmenu.paper.render.CursorStyle
import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PointerControllerTest {
    private val settings = PointerSettings(
        PointerPolicy.PLAYER_CHOICE, PointerMode.TOUCH,
        sensitivityX = 2.0, sensitivityY = 3.0, clampMargin = 3.0,
        cursorStyle = CursorStyle(12.0, 5.0),
    )

    @Test
    fun `virtual cursor wraps yaw and clamps to canvas without changing menu semantics`() {
        val canvas = Canvas(width = 100.0, height = 60.0)
        val wrapped = updateVirtualCursor(MenuPoint(0.0, 0.0), 359.0, -2.0, canvas, settings)
        assertEquals(-2.0, wrapped.x, 1e-9)
        assertEquals(6.0, wrapped.y, 1e-9)

        val clamped = updateVirtualCursor(MenuPoint(0.0, 0.0), 200.0, -200.0, canvas, settings)
        assertEquals(-47.0, clamped.x, 1e-9)
        assertEquals(27.0, clamped.y, 1e-9)

        val malformed = updateVirtualCursor(MenuPoint(4.0, -5.0), Double.NaN, 1.0, canvas, settings)
        assertEquals(MenuPoint(4.0, -5.0), malformed)
    }

    @Test
    fun `pointer configuration rejects ambiguous policy and unsafe cursor values`() {
        val invalidPolicy = YamlConfiguration().apply { set("mouse.policy", "automatic") }
        assertThrows(IllegalArgumentException::class.java) { PointerSettingsLoader.from(invalidPolicy) }

        val invalidSize = YamlConfiguration().apply { set("mouse.cursor.size", 0) }
        assertThrows(IllegalArgumentException::class.java) { PointerSettingsLoader.from(invalidSize) }

        val legacyAppearance = YamlConfiguration().apply {
            set("mouse.cursor.glyph", "legacy")
            set("mouse.cursor.color", "broken")
            set("mouse.cursor.opacity", 1)
        }
        assertEquals(CursorStyle(12.0, 5.0), PointerSettingsLoader.from(legacyAppearance).cursorStyle)

        val wrongType = YamlConfiguration().apply { set("mouse.cursor.sensitivity-x", "fast") }
        assertThrows(IllegalArgumentException::class.java) { PointerSettingsLoader.from(wrongType) }

        val unknown = YamlConfiguration().apply { set("mouse.cursor.teleport", true) }
        assertThrows(IllegalArgumentException::class.java) { PointerSettingsLoader.from(unknown) }
    }

    @Test
    fun `pointer configuration accepts professional touch and mouse names`() {
        val touch = YamlConfiguration().apply {
            set("mouse.policy", "force-touch")
            set("mouse.default", "touch")
        }
        assertEquals(PointerPolicy.FORCE_TOUCH, PointerSettingsLoader.from(touch).policy)
        assertEquals(PointerMode.TOUCH, PointerSettingsLoader.from(touch).defaultMode)

        val mouse = YamlConfiguration().apply {
            set("mouse.policy", "force-mouse")
            set("mouse.default", "mouse")
        }
        assertEquals(PointerPolicy.FORCE_MOUSE, PointerSettingsLoader.from(mouse).policy)
        assertEquals(PointerMode.MOUSE, PointerSettingsLoader.from(mouse).defaultMode)
    }

    @Test
    fun `mouse camera angle is stable when encoded again by the entity packet`() {
        assertEquals(43.59375f, networkCameraAngle(44.9f))
        assertEquals(-1.40625f, networkCameraAngle(-0.1f))

        for (packed in -128..127) {
            val visible = packed * (360.0f / 256.0f)
            assertEquals(visible, networkCameraAngle(visible))
        }
    }
}

class SyntheticPositionPacketFactoryTest {
    enum class Relative { X, Y, Z, Y_ROT, X_ROT, DELTA_X, DELTA_Y, DELTA_Z, ROTATE_DELTA }

    class LegacyPacket(
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float,
        val pitch: Float,
        val relatives: Set<Relative>,
        val id: Int,
    )

    class Vector3(val x: Double, val y: Double, val z: Double)
    class Change(val position: Vector3, val velocity: Vector3, val yaw: Float, val pitch: Float)
    class ModernPacket(val id: Int, val change: Change, val relatives: Set<Relative>)

    @Test
    fun `legacy position packet preserves rotation for polling and replaces it for recentring`() {
        val factory = SyntheticPositionPacketFactory(LegacyPacket::class.java)
        val poll = factory.create(-1, null, null) as LegacyPacket
        val reset = factory.create(-2, 45.0f, 0.0f) as LegacyPacket

        assertEquals(Relative.entries.toSet(), poll.relatives)
        assertEquals(setOf(Relative.X, Relative.Y, Relative.Z, Relative.DELTA_X, Relative.DELTA_Y, Relative.DELTA_Z), reset.relatives)
        assertEquals(45.0f, reset.yaw)
        assertEquals(0.0f, reset.pitch)
    }

    @Test
    fun `modern position packet builds nested change while keeping position relative`() {
        val factory = SyntheticPositionPacketFactory(ModernPacket::class.java)
        val poll = factory.create(-3, null, null) as ModernPacket
        val reset = factory.create(-4, -90.0f, 10.0f) as ModernPacket

        assertEquals(Relative.entries.toSet(), poll.relatives)
        assertEquals(setOf(Relative.X, Relative.Y, Relative.Z, Relative.DELTA_X, Relative.DELTA_Y, Relative.DELTA_Z), reset.relatives)
        assertEquals(-90.0f, reset.change.yaw)
        assertEquals(10.0f, reset.change.pitch)
        assertEquals(0.0, reset.change.position.x)
        assertEquals(0.0, reset.change.velocity.z)
    }

    @Test
    fun `absolute exit sync replaces predicted client position on both packet layouts`() {
        val legacy = SyntheticPositionPacketFactory(LegacyPacket::class.java)
            .createAbsolute(-5, 12.25, 64.0, -8.5, 135.0f, -20.0f) as LegacyPacket
        assertEquals(12.25, legacy.x)
        assertEquals(64.0, legacy.y)
        assertEquals(-8.5, legacy.z)
        assertEquals(135.0f, legacy.yaw)
        assertEquals(-20.0f, legacy.pitch)
        assertEquals(emptySet<Relative>(), legacy.relatives)

        val modern = SyntheticPositionPacketFactory(ModernPacket::class.java)
            .createAbsolute(-6, -3.0, 72.5, 9.0, -45.0f, 15.0f) as ModernPacket
        assertEquals(-3.0, modern.change.position.x)
        assertEquals(72.5, modern.change.position.y)
        assertEquals(9.0, modern.change.position.z)
        assertEquals(-45.0f, modern.change.yaw)
        assertEquals(15.0f, modern.change.pitch)
        assertEquals(emptySet<Relative>(), modern.relatives)
    }
}
