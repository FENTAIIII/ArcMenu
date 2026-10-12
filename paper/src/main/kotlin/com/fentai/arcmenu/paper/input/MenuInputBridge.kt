package com.fentai.arcmenu.paper.input

import com.fentai.arcmenu.paper.runtime.PointerButton
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.messaging.PluginMessageListener

/** Optional shader-free input from the shared editor while a menu camera owns the player. */
internal class MenuInputBridge(
    private val plugin: Plugin,
    private val active: (Player) -> Boolean,
    private val activate: (Player, PointerButton, Boolean) -> Unit,
) : PluginMessageListener, AutoCloseable {
    init {
        plugin.server.messenger.registerIncomingPluginChannel(plugin, CHANNEL, this)
        plugin.server.messenger.registerOutgoingPluginChannel(plugin, CHANNEL)
    }

    fun available(player: Player): Boolean = CHANNEL in player.listeningPluginChannels

    fun state(player: Player, enabled: Boolean) {
        if (player.isOnline && available(player)) player.sendPluginMessage(plugin, CHANNEL,
            byteArrayOf(1, if (enabled) 1 else 0))
    }

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel != CHANNEL || !active(player)) return
        val input = decodeMenuInput(message) ?: return
        activate(player, input.first, input.second)
    }

    override fun close() {
        plugin.server.messenger.unregisterIncomingPluginChannel(plugin, CHANNEL, this)
        plugin.server.messenger.unregisterOutgoingPluginChannel(plugin, CHANNEL)
    }

    companion object { const val CHANNEL = "arcmenu:menu_input" }
}

internal fun decodeMenuInput(message: ByteArray): Pair<PointerButton, Boolean>? {
    if (message.size != 3 || message[0] != 1.toByte() || message[2].toInt() !in 0..1) return null
    val button = PointerButton.entries.getOrNull(message[1].toInt()) ?: return null
    return button to (message[2] == 1.toByte())
}
