package com.fentai.arcmenu.paper.runtime

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/** Adds new settings without replacing any explicitly configured value. */
internal object ConfigDefaults {
    fun load(plugin: JavaPlugin) {
        plugin.saveDefaultConfig()
        val file = plugin.dataFolder.resolve("config.yml")
        val existing = YamlConfiguration().apply { load(file) }
        val stream = requireNotNull(plugin.getResource("config.yml")) { "Missing bundled config.yml" }
        val defaults = InputStreamReader(stream, StandardCharsets.UTF_8).use(YamlConfiguration::loadConfiguration)
        if (mergeMissing(existing, defaults)) existing.save(file)
        plugin.reloadConfig()
    }

    internal fun mergeMissing(existing: ConfigurationSection, defaults: ConfigurationSection): Boolean {
        var changed = false
        defaults.getKeys(false).forEach { key ->
            if (!existing.contains(key, true)) {
                val section = defaults.getConfigurationSection(key)
                if (section == null) existing.set(key, defaults.get(key))
                else mergeMissing(existing.createSection(key), section)
                existing.setComments(key, defaults.getComments(key))
                existing.setInlineComments(key, defaults.getInlineComments(key))
                changed = true
            } else {
                val source = defaults.getConfigurationSection(key)
                val target = existing.getConfigurationSection(key)
                if (source != null && target != null && mergeMissing(target, source)) changed = true
            }
        }
        return changed
    }
}
