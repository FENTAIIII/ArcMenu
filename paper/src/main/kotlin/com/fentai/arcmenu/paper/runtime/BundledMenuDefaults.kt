package com.fentai.arcmenu.paper.runtime

import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Files
import java.nio.file.Path

/** All bundled samples share one installation marker; upgrades respect deletions. */
internal object BundledMenuDefaults {
    fun install(plugin: JavaPlugin) = install(plugin.dataFolder.toPath().resolve("menus")) { resource ->
        plugin.saveResource(resource, false)
    }

    internal fun install(directory: Path, saveResource: (String) -> Unit) {
        val marker = directory.resolveSibling(".menu-defaults-installed-v1")
        val newDirectory = !Files.exists(directory) && !Files.exists(marker)
        Files.createDirectories(directory)
        Files.createDirectories(directory.resolveSibling("images"))
        Files.createDirectories(directory.resolveSibling("templates"))
        if (newDirectory) {
            resources.forEach(saveResource)
        }
        if (!Files.exists(marker)) Files.writeString(marker, "ArcMenu bundled menus were installed once.\n")
    }

    internal val resources = listOf(
        "menus/example.yml", "menus/details.yml", "menus/m4-resources.yml", "menus/m5-animation.yml",
        "animations.yml", "templates/card.yml", "images/example.png", "images/example.gif",
        "images/mouse/mouse.png", "images/mouse/choose.png", "images/ce/topaz_background.png", "images/ce/topaz_frame.png",
    )
}
