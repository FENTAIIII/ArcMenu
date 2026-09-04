package com.fentai.arcmenu.paper.runtime

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.inventory.meta.SkullMeta
import java.util.Locale
import kotlin.random.Random

class ItemActions {
    private enum class Trait { MATERIAL, AMOUNT, DATA, MODEL_DATA, NAME, LORE, HEAD }
    private data class Requirement(val trait: Trait, val opposed: Boolean, val value: String)
    private data class Matcher(val requirements: List<Requirement>) {
        val amount: Int = requirements.firstOrNull { it.trait == Trait.AMOUNT }?.value?.toIntOrNull()?.coerceIn(1, 99) ?: 1
        fun matches(item: ItemStack): Boolean = requirements.filter { it.trait != Trait.AMOUNT }.all { requirement ->
            val matched = when (requirement.trait) {
                Trait.MATERIAL -> item.type.name.equals(requirement.value.substringAfter(':'), true)
                Trait.DATA -> (item.itemMeta as? Damageable)?.damage == requirement.value.toIntOrNull()
                Trait.MODEL_DATA -> item.itemMeta?.let { it.hasCustomModelData() && it.customModelData == requirement.value.toIntOrNull() } == true
                Trait.NAME -> item.itemMeta?.displayName?.contains(legacy(requirement.value), true) == true
                Trait.LORE -> item.itemMeta?.lore?.any { it.contains(legacy(requirement.value), true) } == true
                Trait.HEAD -> (item.itemMeta as? SkullMeta)?.owningPlayer?.name.equals(requirement.value, true)
                Trait.AMOUNT -> true
            }
            if (requirement.opposed) !matched else matched
        }
    }

    fun give(player: Player, specification: String): Boolean {
        val matchers = parse(specification) ?: return false
        for (matcher in matchers) {
            if (matcher.requirements.any { it.opposed }) return false
            val materialName = matcher.requirements.firstOrNull { it.trait == Trait.MATERIAL }?.value ?: "BEDROCK"
            val material = Material.matchMaterial(materialName, true) ?: return false
            val item = ItemStack(material)
            val meta = item.itemMeta
            for (requirement in matcher.requirements) when (requirement.trait) {
                Trait.DATA -> (meta as? Damageable)?.damage = requirement.value.toIntOrNull()?.coerceAtLeast(0) ?: 0
                Trait.MODEL_DATA -> meta.setCustomModelData(requirement.value.toIntOrNull())
                Trait.NAME -> meta.setDisplayName(legacy(requirement.value))
                Trait.LORE -> meta.lore = requirement.value.split("\\n").map(::legacy)
                Trait.HEAD -> if (meta is SkullMeta) meta.owningPlayer = player.server.getOfflinePlayer(requirement.value)
                else -> Unit
            }
            item.itemMeta = meta
            var remaining = matcher.amount
            while (remaining > 0) {
                item.amount = minOf(remaining, material.maxStackSize.coerceAtLeast(1))
                player.inventory.addItem(item.clone()).values.forEach { player.world.dropItem(player.location, it) }
                remaining -= item.amount
            }
        }
        return true
    }

    fun take(player: Player, specification: String): Boolean {
        val matchers = parse(specification) ?: return false
        for (matcher in matchers) {
            var remaining = matcher.amount
            for (slot in player.inventory.storageContents.indices) {
                val item = player.inventory.storageContents[slot] ?: continue
                if (!matcher.matches(item)) continue
                val removed = minOf(remaining, item.amount)
                item.amount -= removed
                if (item.amount <= 0) player.inventory.setItem(slot, null)
                remaining -= removed
                if (remaining == 0) break
            }
            if (remaining > 0) return false
        }
        return true
    }

    fun repair(player: Player, targets: String) {
        targets.split(';').flatMap { selected(player, it.trim()) }.forEach { item ->
            val meta = item.itemMeta as? Damageable ?: return@forEach
            meta.damage = 0
            item.itemMeta = meta
        }
        @Suppress("DEPRECATION")
        player.updateInventory()
    }

    fun enchant(player: Player, specification: String): Boolean {
        var success = true
        specification.split(';').forEach { entry ->
            val split = entry.trim().split(Regex("[, ]+"), limit = 4)
            if (split.size < 3) { success = false; return@forEach }
            val enchantment = enchantment(split[1]) ?: run { success = false; return@forEach }
            val range = split[2].split('-', limit = 2).mapNotNull(String::toIntOrNull)
            val level = when (range.size) {
                1 -> range[0]
                2 -> Random.nextInt(minOf(range[0], range[1]), maxOf(range[0], range[1]) + 1)
                else -> 0
            }
            if (level <= 0) { success = false; return@forEach }
            selected(player, split[0]).forEach { it.addUnsafeEnchantment(enchantment, level) }
        }
        return success
    }

    @Suppress("DEPRECATION")
    private fun enchantment(value: String): Enchantment? {
        val normalized = value.lowercase(Locale.ROOT)
        val key = NamespacedKey.fromString(if (':' in normalized) normalized else "minecraft:$normalized")
        Registry.ENCHANTMENT.get(key ?: return null)?.let { return it }
        val alias = normalized.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]+"), "_")
        return Registry.ENCHANTMENT.firstOrNull {
            it.key.value().uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]+"), "_") == alias
        }
    }

    private fun selected(player: Player, target: String): List<ItemStack> = when (target.lowercase()) {
        "all", "inv" -> player.inventory.contents.filterNotNull()
        "armor" -> player.inventory.armorContents.filterNotNull()
        "hand", "mainhand" -> listOf(player.inventory.itemInMainHand).filter { it.type != Material.AIR }
        "offhand" -> listOf(player.inventory.itemInOffHand).filter { it.type != Material.AIR }
        "helmet" -> listOfNotNull(player.inventory.helmet)
        "chestplate" -> listOfNotNull(player.inventory.chestplate)
        "leggings" -> listOfNotNull(player.inventory.leggings)
        "boots" -> listOfNotNull(player.inventory.boots)
        else -> target.toIntOrNull()?.let { listOfNotNull(player.inventory.getItem(it)) }.orEmpty()
    }

    private fun parse(specification: String): List<Matcher>? {
        val groups = mutableListOf<Matcher>()
        for (group in specification.split(Regex("\\s*;\\s*"))) {
            val requirements = mutableListOf<Requirement>()
            for (rawTrait in group.split(',')) {
                val pair = rawTrait.split(':', '=', limit = 2)
                if (pair.size != 2) return null
                val rawName = pair[0].trim()
                val opposed = rawName.startsWith('!')
                val name = rawName.removePrefix("!").lowercase().replace('_', '-')
                val trait = when (name) {
                    "mat", "mats", "material", "materials" -> Trait.MATERIAL
                    "amount", "amounts", "amt", "amts" -> Trait.AMOUNT
                    "data", "datas" -> Trait.DATA
                    "model-data", "model-datas", "modeldata", "modeldatas" -> Trait.MODEL_DATA
                    "name", "names" -> Trait.NAME
                    "lore", "lores" -> Trait.LORE
                    "head", "heads", "skull", "skulls", "texture", "textures" -> Trait.HEAD
                    else -> return null
                }
                requirements += Requirement(trait, opposed, pair[1].trim())
            }
            if (requirements.isNotEmpty()) groups += Matcher(requirements)
        }
        return groups.takeIf(List<Matcher>::isNotEmpty)
    }

    private companion object {
        val serializer = LegacyComponentSerializer.legacyAmpersand()
        fun legacy(value: String) = LegacyComponentSerializer.legacySection().serialize(serializer.deserialize(value))
    }
}
