package com.fentai.arcmenu.paper.input

import com.fentai.arcmenu.paper.platform.ArcScheduler
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.AnvilInventory
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MenuType
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/** Native EditBox/IME input. Closing the anvil ends editing, never submits the Arcitx buffer. */
internal class ArcitxAnvilService(
    private val plugin: Plugin,
    private val onChanged: (Player, String) -> Unit,
    private val onFinished: (Player) -> Unit,
) : Listener, AutoCloseable {
    private data class Editing(val player: Player, val inventory: AnvilInventory, var initialized: Boolean = false)
    private val edits = ConcurrentHashMap<UUID, Editing>()
    @Volatile private var closed = false

    init { plugin.server.pluginManager.registerEvents(this, plugin) }

    fun isEditing(id: UUID): Boolean = edits.containsKey(id)

    /** Community always uses the visible native anvil, on the player's owner. */
    @Suppress("DEPRECATION")
    fun open(player: Player, title: String, initial: String, maxLength: Int): Boolean {
        check(ArcScheduler.owns(player)) { "Native Arcitx operations require the player owner" }
        require(maxLength in 1..50 && initial.length <= maxLength) { "Invalid native Arcitx input length" }
        if (closed || !player.isOnline) return false
        close(player)
        var inventory: AnvilInventory? = null
        try {
            // MenuType creates a native AnvilMenu with virtual reachability on every supported
            // platform. A custom InventoryType.ANVIL can instead use CraftContainer and omit
            // rename handling; HumanEntity.openAnvil is only a Paper API extension.
            val view = MenuType.ANVIL.create(player, title)
            inventory = view.topInventory as? AnvilInventory ?: run {
                return false
            }
            val state = Editing(player, inventory)
            edits[player.uniqueId] = state
            player.openInventory(view)
            if (player.openInventory.topInventory !== inventory) {
                edits.remove(player.uniqueId, state)
                inventory.clear()
                return false
            }
            inventory.maximumRepairCost = Int.MAX_VALUE
            inventory.repairCost = 0
            inventory.setItem(0, inputItem(initial))
            inventory.setItem(2, inputItem(initial))
            state.initialized = true
            return true
        } catch (error: Exception) {
            edits.remove(player.uniqueId)
            inventory?.clear()
            if (inventory != null && player.openInventory.topInventory === inventory) player.closeInventory()
            plugin.logger.log(Level.WARNING, "ArcMenu could not open native Arcitx input", error)
            return false
        }
    }

    /** Clear temporary items BEFORE native AnvilMenu.removed returns inputs to the player. */
    fun close(player: Player, notify: Boolean = false) {
        check(ArcScheduler.owns(player)) { "Native Arcitx operations require the player owner" }
        val state = edits.remove(player.uniqueId) ?: return
        state.inventory.clear()
        if (player.openInventory.topInventory === state.inventory) player.closeInventory()
        if (notify && player.isOnline) onFinished(player)
    }

    @Suppress("DEPRECATION")
    @EventHandler(priority = EventPriority.HIGHEST)
    fun prepare(event: PrepareAnvilEvent) {
        val player = event.view.player as? Player ?: return
        val state = owned(player, event.inventory) ?: return
        if (!state.initialized) return
        val text = event.inventory.renameText ?: ""
        // The native input stays stable: never rewrite slot 0 or reopen on a change.
        // ArcitxBuffer owns Unicode-safe truncation. Native Minecraft already limits to 50.
        onChanged(player, text)
        if (edits[player.uniqueId] !== state) { event.result = null; return }
        event.inventory.repairCost = 0
        event.result = inputItem(text)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun click(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val state = owned(player, event.view.topInventory) ?: return
        event.isCancelled = true
        if (event.rawSlot == 2) ArcScheduler.later(plugin, player, Runnable {
            if (edits[player.uniqueId] === state) close(player, true)
        }, 1)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun drag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        if (owned(player, event.view.topInventory) != null) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun closed(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        val state = owned(player, event.inventory) ?: return
        if (!edits.remove(player.uniqueId, state)) return
        state.inventory.clear()
        if (plugin.isEnabled) ArcScheduler.later(plugin, player, Runnable {
            if (!closed && player.isOnline && !isEditing(player.uniqueId)) onFinished(player)
        }, 1)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun otherWindow(event: InventoryOpenEvent) {
        val player = event.player as? Player ?: return
        val state = edits[player.uniqueId] ?: return
        if (event.inventory !== state.inventory) {
            close(player)
            ArcScheduler.later(plugin, player, Runnable {
                if (!closed && player.isOnline && !isEditing(player.uniqueId)) onFinished(player)
            }, 1)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun quit(event: PlayerQuitEvent) { close(event.player) }

    private fun owned(player: Player, inventory: Inventory): Editing? =
        edits[player.uniqueId]?.takeIf { it.inventory === inventory }

    @Suppress("DEPRECATION")
    private fun inputItem(text: String) = ItemStack(Material.PAPER).apply {
        itemMeta = itemMeta.apply {
            displayName(Component.text(text))
        }
    }

    override fun close() {
        closed = true
        edits.values.toList().forEach { state ->
            ArcScheduler.execute(plugin, state.player, Runnable { close(state.player) })
        }
        HandlerList.unregisterAll(this)
    }
}
