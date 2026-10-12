package com.fentai.arcmenu.paper.render

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MenuHeadMaterialTest {
    @Test fun `head shorthand and explicit base agree after placeholder expansion`() {
        val shorthand = MenuHeadMaterial.parse("<head:%player_name%>".replace("%player_name%", "FENTAI"))
        assertEquals(MenuHeadMaterial("minecraft:player_head", "FENTAI"), shorthand)
        assertEquals(shorthand, MenuHeadMaterial.parse("minecraft:player_head<head:FENTAI>"))
        assertEquals("f1c87161-390c-49a0-9a04-a36459068b0a", MenuHeadMaterial.parse("<head:f1c87161-390c-49a0-9a04-a36459068b0a>").head)
    }

    @Test fun `plain CE materials remain unchanged and malformed heads are rejected`() {
        assertEquals(MenuHeadMaterial("default:gui_head_size_4"), MenuHeadMaterial.parse("default:gui_head_size_4"))
        assertEquals(MenuHeadMaterial("default:gui_head_size_4", "FENTAI"), MenuHeadMaterial.parse("default:gui_head_size_4<head:FENTAI>"))
        for (invalid in listOf("<head:>", "<head:name with spaces>", "<head:FENTAI><head:Other>", "<head:FENTAI"))
            assertThrows(IllegalArgumentException::class.java) { MenuHeadMaterial.parse(invalid) }
    }
}
