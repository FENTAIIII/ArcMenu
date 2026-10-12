package com.fentai.arcmenu.paper.runtime

import com.fentai.arcmenu.paper.input.PointerMode
import com.fentai.arcmenu.core.behavior.ClickInput

enum class PointerButton { LEFT, RIGHT, MIDDLE }

internal fun acceptsActivation(mode: PointerMode, button: PointerButton): Boolean = true

internal fun PointerButton.clickInput(shift: Boolean): ClickInput = when (this) {
    PointerButton.LEFT -> if (shift) ClickInput.SHIFT_LEFT else ClickInput.LEFT
    PointerButton.RIGHT -> if (shift) ClickInput.SHIFT_RIGHT else ClickInput.RIGHT
    PointerButton.MIDDLE -> if (shift) ClickInput.SHIFT_MIDDLE else ClickInput.MIDDLE
}
