package com.fentai.arcmenu.paper

import com.fentai.arcmenu.paper.runtime.soundAlias
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SoundAliasTest {
    @Test fun `legacy enum and modern sound key normalize to the same alias`() {
        assertEquals(soundAlias("block.chest.open"), soundAlias("BLOCK_CHEST_OPEN"))
        assertEquals(soundAlias("ui.button.click"), soundAlias("UI_BUTTON_CLICK"))
    }
}
