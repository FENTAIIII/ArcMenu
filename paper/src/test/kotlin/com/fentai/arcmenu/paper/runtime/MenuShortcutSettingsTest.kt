package com.fentai.arcmenu.paper.runtime

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MenuShortcutSettingsTest {
    @Test
    fun `shift F is enabled by default and can be disabled`() {
        assertTrue(MenuShortcutSettingsLoader.from(YamlConfiguration()).shiftF)

        val disabled = YamlConfiguration().apply { set("shortcuts.shift-f", false) }
        assertFalse(MenuShortcutSettingsLoader.from(disabled).shiftF)
    }

    @Test
    fun `shortcut configuration rejects invalid types and unknown fields`() {
        val invalidType = YamlConfiguration().apply { set("shortcuts.shift-f", "false") }
        assertThrows(IllegalArgumentException::class.java) { MenuShortcutSettingsLoader.from(invalidType) }

        val unknown = YamlConfiguration().apply { set("shortcuts.swap", false) }
        assertThrows(IllegalArgumentException::class.java) { MenuShortcutSettingsLoader.from(unknown) }
    }
}
