package com.fentai.arcmenu.paper.localization

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStreamReader

class LanguageResourcesTest {
    @Test
    fun `bundled server languages have identical nonempty keys`() {
        val chinese = resourceCatalog("/languages/zh_CN.yml")
        val english = resourceCatalog("/languages/en_US.yml")
        assertEquals(chinese.keys, english.keys)
        assertTrue(chinese.size >= 100)
        chinese.forEach { (key, value) ->
            assertEquals(placeholders(value), placeholders(english.getValue(key)), "$key placeholders")
        }
    }

    @Test
    fun `locale normalization and placeholders are deterministic`() {
        assertEquals("en_US", LanguageManager.normalizeLocale("en-us"))
        assertEquals("zh_CN", LanguageManager.normalizeLocale("ZH_cn"))
        assertEquals("A left B right", LanguageManager.format("A {0} B {1}", arrayOf("left", "right")))
    }

    private fun resourceCatalog(path: String): Map<String, String> {
        val stream = requireNotNull(javaClass.getResourceAsStream(path))
        val yaml = InputStreamReader(stream, Charsets.UTF_8).use(YamlConfiguration::loadConfiguration)
        return yaml.getKeys(true).filterNot(yaml::isConfigurationSection).associateWith { key ->
            assertTrue(yaml.get(key) is String, "$path:$key must be a string")
            requireNotNull(yaml.getString(key)).also { value ->
                assertTrue(value.isNotBlank(), "$path:$key must not be blank")
            }
        }
    }

    private fun placeholders(value: String): Set<String> = Regex("\\{[0-9]+}").findAll(value).map { it.value }.toSet()
}
