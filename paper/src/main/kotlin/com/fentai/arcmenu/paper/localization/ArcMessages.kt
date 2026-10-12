package com.fentai.arcmenu.paper.localization

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.command.CommandSender

/** Consistent plugin replies; menu-authored chat uses its own message text. */
internal object ArcMessages {
    internal const val PREFIX = "&#3c73ff&lA&#4983ff&lr&#5593ff&lc&#62a4ff&lM&#6fb4ff&le&#7bc4ff&ln&#88d4ff&lu&r>> "
    private val ampersand = LegacyComponentSerializer.builder().character('&').hexColors().build()
    private val prefix = legacy(PREFIX)

    fun legacy(value: String): Component = ampersand.deserialize(value.replace('§', '&'))

    fun prefixed(value: String): Component = Component.empty().append(prefix).append(legacy(value))

    fun reply(sender: CommandSender, value: String) = send(sender, prefixed(value))

    fun send(sender: CommandSender, value: Component) = sender.sendMessage(value)
}
