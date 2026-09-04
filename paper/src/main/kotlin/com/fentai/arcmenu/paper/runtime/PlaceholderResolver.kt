package com.fentai.arcmenu.paper.runtime

import com.fentai.arcmenu.paper.localization.LanguageManager
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.lang.reflect.Method
import java.util.Locale

class PlaceholderResolver(
    private val plugin: Plugin,
    private val state: RuntimeStateStore,
    private val language: LanguageManager,
) {
    private val function = Regex("\\{(meta|m|data|d|globaldata|gdata|g):\\s*([^{}]+)}", RegexOption.IGNORE_CASE)
    private val argument = Regex("\\{([0-9]+)}")
    private val named = Regex("%arcmenu_(meta|data|globaldata|args)_(.+?)%", RegexOption.IGNORE_CASE)
    private var attemptedPlaceholderApi = false
    private var placeholderMethod: Method? = null
    private var placeholderWarningSent = false

    fun expand(player: Player, source: String): String {
        var value = function.replace(source) { match ->
            val scope = when (match.groupValues[1].lowercase()) {
                "meta", "m" -> com.fentai.arcmenu.core.behavior.StateScope.META
                "data", "d" -> com.fentai.arcmenu.core.behavior.StateScope.DATA
                else -> com.fentai.arcmenu.core.behavior.StateScope.GLOBAL
            }
            state.get(player, scope, match.groupValues[2].trim()) ?: "null"
        }
        value = argument.replace(value) { match -> state.arguments(player).getOrNull(match.groupValues[1].toInt()) ?: match.value }
        value = named.replace(value) { match ->
            when (match.groupValues[1].lowercase()) {
                "args" -> state.arguments(player).getOrNull(match.groupValues[2].toIntOrNull() ?: -1)
                "meta" -> state.get(player, com.fentai.arcmenu.core.behavior.StateScope.META, match.groupValues[2])
                "data" -> state.get(player, com.fentai.arcmenu.core.behavior.StateScope.DATA, match.groupValues[2])
                else -> state.get(player, com.fentai.arcmenu.core.behavior.StateScope.GLOBAL, match.groupValues[2])
            } ?: "null"
        }
        value = value
            .replace("%player_name%", player.name, ignoreCase = true)
            .replace("%player_uuid%", player.uniqueId.toString(), ignoreCase = true)
            .replace("%player_world%", player.world.name, ignoreCase = true)
            .replace("%player_x%", number(player.location.x), ignoreCase = true)
            .replace("%player_y%", number(player.location.y), ignoreCase = true)
            .replace("%player_z%", number(player.location.z), ignoreCase = true)
        val method = placeholderApi() ?: return value
        value = try {
            method.invoke(null, player, value) as? String ?: value
        } catch (error: ReflectiveOperationException) {
            if (!placeholderWarningSent) {
                placeholderWarningSent = true
                plugin.logger.warning(language.log(
                    "log.placeholder-expand-failed", error.cause?.message ?: error.message.orEmpty(),
                ))
            }
            value
        }
        return value
    }

    private fun placeholderApi(): Method? {
        if (attemptedPlaceholderApi) return placeholderMethod
        attemptedPlaceholderApi = true
        if (!plugin.server.pluginManager.isPluginEnabled("PlaceholderAPI")) return null
        placeholderMethod = try {
            Class.forName("me.clip.placeholderapi.PlaceholderAPI")
                .getMethod("setPlaceholders", Player::class.java, String::class.java)
        } catch (error: ReflectiveOperationException) {
            plugin.logger.warning(language.log("log.placeholder-connect-failed", error.message.orEmpty()))
            null
        }
        return placeholderMethod
    }

    private fun number(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
}
