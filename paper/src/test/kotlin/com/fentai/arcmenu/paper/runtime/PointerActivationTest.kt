package com.fentai.arcmenu.paper.runtime

import com.fentai.arcmenu.paper.input.PointerMode
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PointerActivationTest {
    @Test
    fun `mouse mode accepts only right click`() {
        assertTrue(acceptsActivation(PointerMode.MOUSE, PointerButton.RIGHT))
        assertFalse(acceptsActivation(PointerMode.MOUSE, PointerButton.LEFT))
    }

    @Test
    fun `touch mode accepts only right click`() {
        assertTrue(acceptsActivation(PointerMode.TOUCH, PointerButton.RIGHT))
        assertFalse(acceptsActivation(PointerMode.TOUCH, PointerButton.LEFT))
    }
}
