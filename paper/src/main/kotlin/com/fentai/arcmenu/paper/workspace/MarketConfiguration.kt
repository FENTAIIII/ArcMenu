package com.fentai.arcmenu.paper.workspace

import org.bukkit.configuration.ConfigurationSection

/** Missing settings inherit bundled defaults; explicitly empty settings remain disabled. */
internal object MarketConfiguration {
    private const val LEGACY_URL = "https://114.28.145.65:10051"
    private const val LEGACY_CERTIFICATE = "4631bfe2dd9e912d8f4d6a7cb7e043c54dbb11a03067e049b72de6204e8e18f9"

    private fun usesLegacyDefaults(config: ConfigurationSection): Boolean =
        config.getString("marketplace.url") == LEGACY_URL &&
            config.getString("marketplace.certificate-sha256") == LEGACY_CERTIFICATE

    fun url(config: ConfigurationSection): String =
        if (usesLegacyDefaults(config)) config.root?.defaults?.getString("marketplace.url")
            ?: config.getString("marketplace.url").orEmpty()
        else config.getString("marketplace.url").orEmpty()

    fun certificateSha256(config: ConfigurationSection): String =
        if (usesLegacyDefaults(config)) config.root?.defaults?.getString("marketplace.certificate-sha256")
            ?: config.getString("marketplace.certificate-sha256").orEmpty()
        else config.getString("marketplace.certificate-sha256").orEmpty()
}
