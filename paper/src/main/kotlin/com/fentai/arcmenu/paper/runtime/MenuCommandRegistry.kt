package com.fentai.arcmenu.paper.runtime

import com.fentai.arcmenu.core.config.MenuEntrypointCompiler
import com.fentai.arcmenu.core.config.MenuEntrypoints
import com.fentai.arcmenu.core.config.MenuFormatException
import com.fentai.arcmenu.core.model.MenuDocument
import com.fentai.arcmenu.paper.localization.LanguageManager
import net.kyori.adventure.text.Component
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.Locale

/** Owns the live Bukkit commands declared by the currently published menu set. */
class MenuCommandRegistry(
    private val plugin: Plugin,
    private val language: LanguageManager,
    private val opener: (Player, String, List<String>) -> Boolean,
) : AutoCloseable {
    private val commandMap = plugin.server.commandMap
    private val fallbackPrefix = plugin.name.lowercase(Locale.ROOT)
    private val registered = linkedMapOf<String, Command>()
    private var current: MenuEntrypoints? = null

    val mainMenuId: String? get() = current?.mainMenuId

    fun validate(documents: Map<String, MenuDocument>) {
        val entrypoints = MenuEntrypointCompiler.compile(documents)
        val owned = registered.values.toSet()
        entrypoints.commands.keys.forEach { label ->
            listOf(label, "$fallbackPrefix:$label").forEach { key ->
                val existing = commandMap.knownCommands[key]
                if (existing != null && owned.none { it === existing }) {
                    val document = documents.values.first { label in it.definition.openCommands }
                    throw MenuFormatException(
                        "${document.sourceName}.open-commands: /$label 与已注册命令 ${existing.name} 冲突",
                    )
                }
            }
        }
    }

    fun replace(documents: Map<String, MenuDocument>) {
        val next = MenuEntrypointCompiler.compile(documents)
        validate(documents)
        if (next == current) return
        val previous = current
        unregisterAll()
        try {
            registerAll(next)
            current = next
        } catch (error: Exception) {
            unregisterAll()
            current = null
            if (previous != null) {
                runCatching {
                    registerAll(previous)
                    current = previous
                }.onFailure { rollback ->
                    plugin.logger.severe("ArcMenu custom command rollback failed: ${rollback.message}")
                }
            }
            refreshClientCommands()
            throw error
        }
        refreshClientCommands()
    }

    private fun registerAll(entrypoints: MenuEntrypoints) {
        entrypoints.commands.forEach { (label, menuId) ->
            val command = MenuOpenCommand(label, menuId)
            registered[label] = command
            check(commandMap.register(fallbackPrefix, command)) { "无法注册菜单命令 /$label；命令已被占用" }
        }
    }

    private fun unregisterAll() {
        if (registered.isEmpty()) return
        val commands = registered.values.toSet()
        unregisterCommandMappings(commandMap.knownCommands, commands)
        commands.forEach { it.unregister(commandMap) }
        registered.clear()
    }

    private fun refreshClientCommands() = plugin.server.onlinePlayers.forEach(Player::updateCommands)

    override fun close() {
        unregisterAll()
        current = null
    }

    private inner class MenuOpenCommand(label: String, private val menuId: String) : Command(
        label,
        "Open the ArcMenu menu $menuId",
        "/$label",
        emptyList(),
    ) {
        override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>): Boolean {
            val player = sender as? Player
            if (player == null) {
                sender.sendMessage(Component.text("[ArcMenu] ${language.text(sender, "command.custom.player-only")}"))
                return true
            }
            opener(player, menuId, args.toList())
            return true
        }
    }
}

/**
 * Paper 26's Brigadier forwarding map supports direct removal but its entry iterator
 * deliberately rejects Iterator.remove(), which also makes Collection.removeIf fail.
 */
internal fun unregisterCommandMappings(knownCommands: MutableMap<String, Command>, owned: Set<Command>) {
    val mappings = knownCommands.entries
        .filter { (_, command) -> owned.any { it === command } }
        .map { it.key to it.value }
    mappings.forEach { (label, command) -> knownCommands.remove(label, command) }
}
