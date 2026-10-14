package com.fentai.arcmenu.paper.runtime

import com.fentai.arcmenu.core.config.MenuParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ArcitxExampleTest {
    @Test
    fun `deployable example binds its field to a separate submit button`() {
        val source = javaClass.getResource("/menus/arcitx-test.yml")!!.readText()
        val menu = MenuParser().parse(source, "arcitx-test.yml").definition
        val input = menu.backend.first { it.id == "edit-input" }.arcitx!!
        assertEquals("input-buffer", input.textId)
        assertEquals("submit-input", input.submitId)
        assertEquals(1, menu.backend.first { it.id == input.submitId }.actions
            .matching(PointerButton.LEFT.clickInput(false)).size)
    }
}
