package com.fentai.arcmenu.paper.input

import com.fentai.arcmenu.paper.runtime.PointerButton
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MenuInputBridgeTest {
    @Test fun `client input distinguishes all six clicks and rejects malformed packets`() {
        for (button in PointerButton.entries) for (shift in 0..1) {
            assertEquals(button to (shift == 1), decodeMenuInput(byteArrayOf(1, button.ordinal.toByte(), shift.toByte())))
        }
        for (invalid in listOf(byteArrayOf(), byteArrayOf(1, 0), byteArrayOf(2, 0, 0),
                byteArrayOf(1, 3, 0), byteArrayOf(1, -1, 0), byteArrayOf(1, 0, 2), byteArrayOf(1, 0, 0, 0))) {
            assertNull(decodeMenuInput(invalid))
        }
    }
}
