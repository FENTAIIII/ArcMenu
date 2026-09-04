package com.fentai.arcmenu.paper.runtime

import com.fentai.arcmenu.core.behavior.StateScope
import com.fentai.arcmenu.paper.localization.LanguageManager
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.Properties
import java.util.UUID

/** TrMenu-style meta/data/global-data storage without taking a dependency on TrMenu. */
class RuntimeStateStore(private val plugin: Plugin, private val language: LanguageManager) : AutoCloseable {
    private val meta = mutableMapOf<UUID, MutableMap<String, String>>()
    private val data = mutableMapOf<UUID, MutableMap<String, String>>()
    private val global = mutableMapOf<String, String>()
    private val arguments = mutableMapOf<UUID, List<String>>()
    private val file = plugin.dataFolder.toPath().resolve("data.properties")
    private var pendingSave: BukkitTask? = null

    init { load() }

    fun get(player: Player, scope: StateScope, key: String): String? = when (scope) {
        StateScope.META -> meta[player.uniqueId]?.get(key)
        StateScope.DATA -> data[player.uniqueId]?.get(key)
        StateScope.GLOBAL -> global[key]
    }

    fun set(player: Player, scope: StateScope, key: String, value: String) {
        when (scope) {
            StateScope.META -> meta.computeIfAbsent(player.uniqueId) { mutableMapOf() }[key] = value
            StateScope.DATA -> data.computeIfAbsent(player.uniqueId) { mutableMapOf() }[key] = value
            StateScope.GLOBAL -> global[key] = value
        }
        if (scope != StateScope.META) requestSave()
    }

    fun remove(player: Player, scope: StateScope, pattern: Regex) {
        val target = when (scope) {
            StateScope.META -> meta[player.uniqueId]
            StateScope.DATA -> data[player.uniqueId]
            StateScope.GLOBAL -> global
        } ?: return
        target.keys.filter(pattern::matches).forEach(target::remove)
        if (scope != StateScope.META) requestSave()
    }

    fun arguments(player: Player): List<String> = arguments[player.uniqueId].orEmpty().toList()
    fun setArguments(player: Player, values: List<String>) { arguments[player.uniqueId] = values.toList() }
    fun clearArguments(player: Player) { arguments.remove(player.uniqueId) }

    private fun requestSave() {
        if (pendingSave != null) return
        pendingSave = plugin.server.scheduler.runTaskLater(plugin, Runnable {
            pendingSave = null
            save()
        }, 20L)
    }

    private fun load() {
        if (!Files.isRegularFile(file)) return
        try {
            val properties = Properties().also { Files.newBufferedReader(file, StandardCharsets.UTF_8).use(it::load) }
            for ((rawKey, rawValue) in properties) {
                val key = rawKey.toString()
                val value = rawValue.toString()
                when {
                    key.startsWith("g.") -> global[decode(key.substring(2))] = value
                    key.startsWith("p.") -> {
                        val parts = key.split('.', limit = 3)
                        if (parts.size == 3) data.computeIfAbsent(UUID.fromString(parts[1])) { mutableMapOf() }[decode(parts[2])] = value
                    }
                }
            }
        } catch (error: Exception) {
            plugin.logger.warning(language.log("log.state-read-failed", error.message.orEmpty()))
        }
    }

    private fun save() {
        try {
            Files.createDirectories(file.parent)
            val properties = Properties()
            global.forEach { (key, value) -> properties["g.${encode(key)}"] = value }
            data.forEach { (uuid, values) -> values.forEach { (key, value) -> properties["p.$uuid.${encode(key)}"] = value } }
            val temporary = file.resolveSibling("${file.fileName}.tmp")
            Files.newBufferedWriter(temporary, StandardCharsets.UTF_8).use { properties.store(it, "ArcMenu persistent data") }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Exception) {
            plugin.logger.warning(language.log("log.state-save-failed", error.message.orEmpty()))
        }
    }

    override fun close() {
        pendingSave?.cancel()
        pendingSave = null
        save()
        meta.clear()
        arguments.clear()
    }

    private fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    private fun decode(value: String) = String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}
