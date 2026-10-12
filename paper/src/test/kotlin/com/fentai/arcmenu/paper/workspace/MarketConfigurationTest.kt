package com.fentai.arcmenu.paper.workspace

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class MarketConfigurationTest {
    private fun oldConfiguration(): YamlConfiguration {
        val resource = requireNotNull(javaClass.classLoader.getResourceAsStream("config.yml"))
        val defaults = resource.bufferedReader().use { YamlConfiguration.loadConfiguration(it) }
        return YamlConfiguration().apply {
            set("language", "en_US")
            setDefaults(defaults)
        }
    }

    @Test
    fun `an existing configuration without marketplace settings inherits the bundled endpoint and pin`() {
        val config = oldConfiguration()
        assertFalse(config.contains("marketplace", true))

        assertEquals("https://market.myarc.icu:10051", MarketConfiguration.url(config))
        val certificate = MarketConfiguration.certificateSha256(config)
        assertEquals(config.defaults!!.getString("marketplace.certificate-sha256"), certificate)
        assertEquals("", certificate)
        assertFalse(config.contains("marketplace", true))
    }

    @Test
    fun `the previous bundled IP endpoint moves to the domain without rewriting local settings`() {
        val config = oldConfiguration().apply {
            set("marketplace.url", "https://114.28.145.65:10051")
            set("marketplace.certificate-sha256", "4631bfe2dd9e912d8f4d6a7cb7e043c54dbb11a03067e049b72de6204e8e18f9")
        }
        assertEquals("https://market.myarc.icu:10051", MarketConfiguration.url(config))
        assertEquals("", MarketConfiguration.certificateSha256(config))
        assertEquals("https://114.28.145.65:10051", config.getString("marketplace.url"))
    }

    @Test
    fun `an independently configured certificate is not replaced during the domain migration`() {
        val config = oldConfiguration().apply {
            set("marketplace.url", "https://114.28.145.65:10051")
            set("marketplace.certificate-sha256", "custom-pin")
        }
        assertEquals("https://114.28.145.65:10051", MarketConfiguration.url(config))
        assertEquals("custom-pin", MarketConfiguration.certificateSha256(config))
    }

    @Test
    fun `explicitly empty settings override bundled defaults`() {
        val config = oldConfiguration().apply {
            set("marketplace.url", "")
            set("marketplace.certificate-sha256", "")
        }

        assertEquals("", MarketConfiguration.url(config))
        assertEquals("", MarketConfiguration.certificateSha256(config))
    }

    @Test
    fun `a custom endpoint retains its configured pin and absent settings stay disabled without defaults`() {
        val config = oldConfiguration().apply {
            set("marketplace.url", "https://market.example.org")
            set("marketplace.certificate-sha256", "custom-pin")
        }

        assertEquals("https://market.example.org", MarketConfiguration.url(config))
        assertEquals("custom-pin", MarketConfiguration.certificateSha256(config))
        assertEquals("", MarketConfiguration.url(YamlConfiguration()))
        assertEquals("", MarketConfiguration.certificateSha256(YamlConfiguration()))
    }
}
