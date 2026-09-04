package com.fentai.arcmenu.paper.localization

import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Server-side messages use one stable key namespace. Player messages may follow
 * the locale reported by the Minecraft client; console and logs use the configured default.
 */
class LanguageManager(private val plugin: JavaPlugin) {
    private val languageDirectory = plugin.dataFolder.toPath().resolve("languages")
    private val warnedMissing = ConcurrentHashMap.newKeySet<String>()
    private var catalogs: Map<String, Map<String, String>> = emptyMap()
    private var defaultLocale = BUILTIN_DEFAULT
    private var followPlayerLocale = true

    fun load() {
        Files.createDirectories(languageDirectory)
        BUNDLED.forEach { locale ->
            val path = languageDirectory.resolve("$locale.yml")
            if (!Files.exists(path)) plugin.saveResource("languages/$locale.yml", false)
        }

        val section = plugin.config.getConfigurationSection("language")
        section?.getKeys(false)?.firstOrNull { it !in setOf("default", "follow-player-locale") }?.let {
            throw IllegalArgumentException("config.yml language.$it is not a supported field")
        }
        val configuredDefault = plugin.config.get("language.default", BUILTIN_DEFAULT)
        require(configuredDefault is String) { "config.yml language.default must be a string" }
        val configuredFollow = plugin.config.get("language.follow-player-locale", true)
        require(configuredFollow is Boolean) { "config.yml language.follow-player-locale must be true or false" }

        // Always keep the JAR catalogs as a complete fallback. External built-in files
        // are user overrides and may come from an older ArcMenu version with fewer keys.
        val loaded = linkedMapOf<String, Map<String, String>>()
        BUNDLED.forEach { locale ->
            val stream = requireNotNull(plugin.getResource("languages/$locale.yml")) {
                "Missing bundled ArcMenu language languages/$locale.yml"
            }
            val yaml = InputStreamReader(stream, StandardCharsets.UTF_8).use(YamlConfiguration::loadConfiguration)
            loaded[locale] = catalog(yaml, "bundled language $locale")
        }
        val externalLocales = mutableSetOf<String>()
        Files.list(languageDirectory).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".yml", true) }
                .sorted()
                .forEach { path ->
                    val locale = normalizeLocale(path.fileName.toString().substringBeforeLast('.'))
                    require(LOCALE.matches(locale)) { "Invalid language filename ${path.fileName}; expected ll_CC.yml" }
                    require(externalLocales.add(locale)) { "Duplicate ArcMenu language locale: $locale" }
                    val yaml = Files.newBufferedReader(path, StandardCharsets.UTF_8).use(YamlConfiguration::loadConfiguration)
                    val values = catalog(yaml, "language $locale")
                    loaded[locale] = loaded[locale].orEmpty() + values
                }
        }
        require(BUILTIN_DEFAULT in loaded) { "Missing required language file $BUILTIN_DEFAULT.yml" }

        val normalizedDefault = normalizeLocale(configuredDefault)
        require(normalizedDefault in loaded) {
            "Configured language.default $normalizedDefault has no matching language file"
        }
        catalogs = loaded.toMap()
        defaultLocale = normalizedDefault
        followPlayerLocale = configuredFollow
        warnedMissing.clear()
    }

    private fun catalog(yaml: YamlConfiguration, label: String): Map<String, String> = linkedMapOf<String, String>().also { values ->
        yaml.getKeys(true).filterNot(yaml::isConfigurationSection).sorted().forEach { key ->
            val value = yaml.get(key)
            require(value is String) { "$label entry $key must be a string" }
            require(value.isNotBlank()) { "$label entry $key must not be blank" }
            values[key] = value
        }
    }

    fun text(sender: CommandSender, key: String, vararg arguments: Any?): String =
        text(if (sender is Player && followPlayerLocale) sender.locale().toString() else defaultLocale, key, *arguments)

    fun text(player: Player, key: String, vararg arguments: Any?): String =
        text(if (followPlayerLocale) player.locale().toString() else defaultLocale, key, *arguments)

    fun log(key: String, vararg arguments: Any?): String = text(defaultLocale, key, *arguments)

    fun locale(player: Player): String = resolveLocale(if (followPlayerLocale) player.locale().toString() else defaultLocale)

    internal fun text(locale: String, key: String, vararg arguments: Any?): String {
        val resolved = resolveLocale(locale)
        val template = catalogs[resolved]?.get(key)
            ?: catalogs[defaultLocale]?.get(key)
            ?: catalogs[BUILTIN_DEFAULT]?.get(key)
        if (template == null) {
            if (warnedMissing.add(key)) plugin.logger.warning("Missing ArcMenu language key: $key")
            return key
        }
        return format(template, arguments)
    }

    private fun resolveLocale(value: String): String {
        val normalized = normalizeLocale(value)
        if (normalized in catalogs) return normalized
        val language = normalized.substringBefore('_')
        return catalogs.keys.firstOrNull { it.substringBefore('_') == language } ?: defaultLocale
    }

    companion object {
        const val BUILTIN_DEFAULT = "zh_CN"
        private val BUNDLED = listOf("zh_CN", "en_US")
        private val LOCALE = Regex("[a-z]{2,3}_[A-Z]{2}")

        internal fun normalizeLocale(value: String): String {
            val parts = value.trim().replace('-', '_').split('_', limit = 3)
            if (parts.size < 2) return value.trim().lowercase(Locale.ROOT)
            return parts[0].lowercase(Locale.ROOT) + "_" + parts[1].uppercase(Locale.ROOT)
        }

        internal fun format(template: String, arguments: Array<out Any?>): String {
            var result = template
            arguments.forEachIndexed { index, value -> result = result.replace("{$index}", value?.toString().orEmpty()) }
            return result
        }
    }
}
