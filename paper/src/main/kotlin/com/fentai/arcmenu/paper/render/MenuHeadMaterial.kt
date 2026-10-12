package com.fentai.arcmenu.paper.render

import org.bukkit.Material
import java.util.UUID

internal data class MenuHeadMaterial(val base: String, val head: String? = null) {
    fun vanilla(): Material? = Material.matchMaterial(base)?.takeIf { it.isItem && !it.isAir }

    companion object {
        fun parse(source: String): MenuHeadMaterial {
            val text = source.trim()
            require(text.isNotEmpty()) { "Item material must not be empty" }
            if ('<' !in text) return MenuHeadMaterial(text)
            val match = Regex("^([^<>]*)<head:([^<>]+)>$", RegexOption.IGNORE_CASE).matchEntire(text)
                ?: throw IllegalArgumentException("Invalid player head material: $source")
            val head = match.groupValues[2].trim()
            require(head.matches(Regex("[a-zA-Z0-9_]{1,16}")) || runCatching { UUID.fromString(head) }.isSuccess) {
                "Player head requires a player name or UUID"
            }
            return MenuHeadMaterial(match.groupValues[1].trim().ifEmpty { "minecraft:player_head" }, head)
        }
    }
}
