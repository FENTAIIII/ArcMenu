package com.fentai.arcmenu.paper.editor

import com.fentai.arcmenu.core.model.Canvas
import com.fentai.arcmenu.protocol.EditorProtocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class EditorCoordinatesTest {
    private val canvas = Canvas(320.0, 180.0)

    private fun pointer(mouseX: Double, mouseY: Double, clientX: Double = 9999.0, clientY: Double = -9999.0) =
        EditorProtocol.Pointer(mouseX, mouseY, 100.0, 50.0, 960.0, 540.0, clientX, clientY)

    @Test
    fun `server independently maps center and all viewport corners`() {
        val samples = listOf(
            pointer(580.0, 320.0) to (0.0 to 0.0),
            pointer(100.0, 50.0) to (-160.0 to 90.0),
            pointer(1060.0, 50.0) to (160.0 to 90.0),
            pointer(100.0, 590.0) to (-160.0 to -90.0),
            pointer(1060.0, 590.0) to (160.0 to -90.0),
        )
        samples.forEach { (input, expected) ->
            val actual = editorCoordinates(input, canvas)
            assertEquals(expected.first, actual.x, 1e-10)
            assertEquals(expected.second, actual.y, 1e-10)
        }
    }

    @Test
    fun `client logical fields cannot change server accepted point`() {
        val actual = editorCoordinates(pointer(820.0, 185.0), canvas)
        assertEquals(80.0, actual.x, 1e-10)
        assertEquals(45.0, actual.y, 1e-10)
    }

    @Test
    fun `non finite invalid viewport and remote points are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            editorCoordinates(pointer(Double.NaN, 50.0), canvas)
        }
        assertThrows(IllegalArgumentException::class.java) {
            editorCoordinates(EditorProtocol.Pointer(10.0, 10.0, 0.0, 0.0, 8.0, 8.0, 0.0, 0.0), canvas)
        }
        assertThrows(IllegalArgumentException::class.java) {
            editorCoordinates(pointer(100_000.0, 50.0), canvas)
        }
    }
}
