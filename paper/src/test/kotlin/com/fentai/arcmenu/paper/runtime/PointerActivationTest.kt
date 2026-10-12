package com.fentai.arcmenu.paper.runtime

import com.fentai.arcmenu.core.behavior.ClickInput
import com.fentai.arcmenu.paper.input.PointerMode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PointerActivationTest {
    @Test fun `all six buttons retain their identity in both pointer modes`() {
        for (mode in PointerMode.entries) for (button in PointerButton.entries) {
            assertTrue(acceptsActivation(mode, button))
            assertEquals(ClickInput.valueOf(button.name), button.clickInput(false))
            assertEquals(ClickInput.valueOf("SHIFT_${button.name}"), button.clickInput(true))
        }
    }
}
