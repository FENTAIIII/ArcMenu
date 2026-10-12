package com.fentai.arcmenu.paper.branding

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import com.fentai.arcmenu.paper.localization.ArcMessages
import org.bukkit.plugin.Plugin

internal object StartupBanner {
    private const val MENU_START_COLUMN = 21
    private val logo = listOf(
        " ###   ####    ####  #   #  #####  #   #  #   #",
        "#   #  #   #  #      ## ##  #      ##  #  #   #",
        "#####  ####   #      # # #  ####   # # #  #   #",
        "#   #  # #    #      #   #  #      #  ##  #   #",
        "#   #  #  #    ####  #   #  #####  #   #   ### ",
    )

    fun show(plugin: Plugin) {
        val console = plugin.server.consoleSender
        val arcColor = TextColor.color(0x2563EB)
        val menuColor = TextColor.color(0x7DD3FC)
        ArcMessages.send(console, Component.empty())
        logo.forEach { line ->
            ArcMessages.send(console, Component.text("  ")
                .append(Component.text(line.take(MENU_START_COLUMN).replace('#', '█'), arcColor))
                .append(Component.text(line.drop(MENU_START_COLUMN).replace('#', '█'), menuColor)))
        }
        ArcMessages.send(console, Component.text("  Arc", arcColor)
            .append(Component.text("Menu ${plugin.description.version}  |  Community Edition", menuColor)))
        ArcMessages.send(console, Component.empty())
    }
}
