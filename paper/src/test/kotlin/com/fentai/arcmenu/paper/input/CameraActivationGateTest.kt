package com.fentai.arcmenu.paper.input

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CameraActivationGateTest {
    @Test
    fun `only the matching activation acknowledgement releases the camera`() {
        val gate = CameraActivationGate()

        gate.expect(-41)
        assertFalse(gate.isReady())
        assertFalse(gate.accept(-40))
        assertFalse(gate.isReady())
        assertTrue(gate.accept(-41))
        assertTrue(gate.isReady())
    }

    @Test
    fun `a new activation closes a previously released gate`() {
        val gate = CameraActivationGate()
        gate.expect(-1)
        gate.accept(-1)
        assertTrue(gate.isReady())

        gate.expect(-2)
        assertFalse(gate.isReady())
    }
}
