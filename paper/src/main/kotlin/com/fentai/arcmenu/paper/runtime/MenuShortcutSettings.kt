package com.fentai.arcmenu.paper.runtime

import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.plugin.Plugin

data class MenuShortcutSettings(
    val shiftF: Boolean = true,
)

object MenuShortcutSettingsLoader {
    fun load(plugin: Plugin): MenuShortcutSettings = from(plugin.config)

    internal fun from(config: FileConfiguration): MenuShortcutSettings {
        config.getConfigurationSection("shortcuts")?.getKeys(false)
            ?.firstOrNull { it != "shift-f" }
            ?.let { throw IllegalArgumentException("config.yml shortcuts.$it 是不支持的字段") }

        val value = config.get("shortcuts.shift-f") ?: true
        require(value is Boolean) { "config.yml shortcuts.shift-f 必须为 true 或 false" }
        return MenuShortcutSettings(shiftF = value)
    }
}
