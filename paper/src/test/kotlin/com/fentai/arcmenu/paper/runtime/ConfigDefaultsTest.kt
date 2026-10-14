package com.fentai.arcmenu.paper.runtime

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigDefaultsTest {
    @Test
    fun `new defaults fill missing nested fields and preserve explicit settings`() {
        val existing = yaml("""
            shortcuts:
              shift-f: false
            language:
              default: en_US
            custom-list: [one, two]
            custom-value: 0
        """.trimIndent())
        existing.setComments("shortcuts.shift-f", listOf("An existing user comment"))
        val defaults = yaml("""
            shortcuts:
              shift-f: true
              close-on-shift-f: true
              close-on-space: true
            language:
              default: zh_CN
              follow-player-locale: true
            custom-list: [three]
            custom-value: 5
        """.trimIndent())
        defaults.setComments("shortcuts.close-on-space", listOf("Close runtime menus with jump input"))
        defaults.setComments("shortcuts.close-on-shift-f", listOf("Close runtime menus with Shift+F"))
        assertTrue(ConfigDefaults.mergeMissing(existing, defaults))
        assertFalse(existing.getBoolean("shortcuts.shift-f"))
        assertTrue(existing.getBoolean("shortcuts.close-on-space"))
        assertTrue(existing.getBoolean("shortcuts.close-on-shift-f"))
        assertEquals("en_US", existing.getString("language.default"))
        assertTrue(existing.getBoolean("language.follow-player-locale"))
        assertEquals(listOf("one", "two"), existing.getStringList("custom-list"))
        assertEquals(0, existing.getInt("custom-value"))
        assertEquals(listOf("An existing user comment"), existing.getComments("shortcuts.shift-f"))
        assertEquals(defaults.getComments("shortcuts.close-on-space"), existing.getComments("shortcuts.close-on-space"))
        assertEquals(defaults.getComments("shortcuts.close-on-shift-f"), existing.getComments("shortcuts.close-on-shift-f"))
        assertFalse(ConfigDefaults.mergeMissing(existing, defaults))
    }

    @Test
    fun `explicit incompatible sections are preserved for validation instead of silently replaced`() {
        val existing = yaml("shortcuts: false")
        val defaults = yaml("shortcuts:\n  shift-f: true\n  close-on-shift-f: true\n  close-on-space: true\n")
        assertFalse(ConfigDefaults.mergeMissing(existing, defaults))
        assertEquals(false, existing.get("shortcuts"))
    }

    @Test
    fun `new sections copy all bundled values and remain stable after serialization`() {
        val existing = YamlConfiguration()
        val defaults = yaml("shortcuts:\n  shift-f: true\n  close-on-shift-f: true\n  close-on-space: true\n")
        assertTrue(ConfigDefaults.mergeMissing(existing, defaults))
        val loaded = yaml(existing.saveToString())
        assertTrue(loaded.getBoolean("shortcuts.shift-f"))
        assertTrue(loaded.getBoolean("shortcuts.close-on-space"))
        assertTrue(loaded.getBoolean("shortcuts.close-on-shift-f"))
        assertFalse(ConfigDefaults.mergeMissing(loaded, defaults))
    }

    @Test
    fun `explicit disabled shift F closing survives default merging and serialization`() {
        val existing = yaml("shortcuts:\n  shift-f: false\n  close-on-shift-f: false\n")
        existing.setComments("shortcuts.close-on-shift-f", listOf("Keep this shortcut disabled"))
        val defaults = yaml("shortcuts:\n  shift-f: true\n  close-on-shift-f: true\n  close-on-space: true\n")
        assertTrue(ConfigDefaults.mergeMissing(existing, defaults))
        val loaded = yaml(existing.saveToString())
        assertFalse(loaded.getBoolean("shortcuts.shift-f"))
        assertFalse(loaded.getBoolean("shortcuts.close-on-shift-f"))
        assertTrue(loaded.getBoolean("shortcuts.close-on-space"))
        assertEquals(listOf("Keep this shortcut disabled"), loaded.getComments("shortcuts.close-on-shift-f"))
        assertFalse(ConfigDefaults.mergeMissing(loaded, defaults))
    }

    private fun yaml(text: String): YamlConfiguration = YamlConfiguration().apply { loadFromString(text) }
}
