package com.fentai.arcmenu.paper.runtime

import com.fentai.arcmenu.paper.input.PointerMode

enum class PointerButton { LEFT, RIGHT }

internal fun acceptsActivation(mode: PointerMode, button: PointerButton): Boolean = when (mode) {
    PointerMode.MOUSE -> button == PointerButton.RIGHT
    PointerMode.TOUCH -> button == PointerButton.RIGHT
}
