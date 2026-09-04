package com.fentai.arcmenu.paper.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ApplicationInputTest {
    @Test
    fun `adjacent hotbar movement becomes one scroll step`() {
        assertEquals(1, hotbarScrollSteps(3, 4))
        assertEquals(-1, hotbarScrollSteps(4, 3))
        assertEquals(1, hotbarScrollSteps(8, 0))
        assertEquals(-1, hotbarScrollSteps(0, 8))
    }

    @Test
    fun `number keys and invalid slots are not mistaken for wheel steps`() {
        assertEquals(0, hotbarScrollSteps(1, 7))
        assertEquals(0, hotbarScrollSteps(4, 4))
        assertEquals(0, hotbarScrollSteps(-1, 0))
        assertEquals(0, hotbarScrollSteps(8, 9))
    }
}
