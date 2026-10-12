package com.fentai.arcmenu.paper.platform

import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin

/** Keeps editor and workspace callbacks on the existing Paper/Folia entity owner. */
internal object ArcScheduler {
    val folia: Boolean = runCatching {
        Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
    }.isSuccess

    fun stopping(): Boolean = Bukkit.isStopping()

    fun owns(entity: Entity): Boolean = if (folia) Bukkit.isOwnedByCurrentRegion(entity) else Bukkit.isPrimaryThread()

    fun execute(plugin: Plugin, entity: Entity, task: Runnable): Boolean {
        if (owns(entity)) { task.run(); return true }
        return entity.scheduler.execute(plugin, task, null, 1L)
    }

    fun later(plugin: Plugin, entity: Entity, task: Runnable, delay: Long) {
        entity.scheduler.runDelayed(plugin, { task.run() }, null, delay.coerceAtLeast(1L))
    }

    fun globalLater(plugin: Plugin, task: Runnable, delay: Long = 1L) {
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { task.run() }, delay.coerceAtLeast(1L))
    }
}
