package com.fentai.arcmenu.paper.render

import com.destroystokyo.paper.profile.PlayerProfile
import com.fentai.arcmenu.paper.resource.CraftEngineBridge
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.plugin.Plugin
import java.util.concurrent.CompletableFuture

internal data class BuiltMenuHeadItem(val item: ItemStack, val profile: CompletableFuture<PlayerProfile>? = null)

internal class MenuHeadItemFactory(plugin: Plugin, private val craftEngine: CraftEngineBridge) {
    private val profiles by lazy { PlayerHeadProfiles(plugin) }

    fun validate(source: String) {
        if ('%' in source || '{' in source) return
        val spec = MenuHeadMaterial.parse(source)
        val vanilla = spec.vanilla()
        require(vanilla != null || craftEngine.hasItem(spec.base)) {
            "Item ${spec.base} does not exist in vanilla Minecraft or CraftEngine"
        }
        require(spec.head == null || vanilla == null || vanilla == Material.PLAYER_HEAD) {
            "head can only be used with player heads"
        }
    }

    fun build(source: String, viewer: Player): BuiltMenuHeadItem {
        val spec = MenuHeadMaterial.parse(source)
        val item = spec.vanilla()?.let(::ItemStack) ?: craftEngine.buildItem(spec.base, viewer)
            ?: throw IllegalArgumentException("Cannot build item ${spec.base}")
        require(spec.head == null || item.itemMeta is SkullMeta) { "head can only be used with player heads" }
        return BuiltMenuHeadItem(item, spec.head?.let { profiles.load(viewer, it) })
    }
}
