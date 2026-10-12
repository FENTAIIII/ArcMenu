package com.fentai.arcmenu.paper.render

import com.destroystokyo.paper.profile.PlayerProfile
import com.fentai.arcmenu.paper.platform.ArcScheduler
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.CompletableFuture

internal fun deliverMenuHeadProfile(
    future: CompletableFuture<PlayerProfile>,
    schedule: (Runnable) -> Unit,
    current: () -> Boolean,
    apply: (PlayerProfile) -> Unit,
) {
    future.whenComplete { profile, error ->
        if (error == null && profile != null) schedule(Runnable { if (current()) apply(profile) })
    }
}

/** Online profiles are read on their owner's region; offline lookup uses Paper's async profile API. */
internal class PlayerHeadProfiles(
    plugin: Plugin,
    private val findOnline: (UUID?, String) -> Player? = { uuid, name ->
        if (uuid != null) Bukkit.getPlayer(uuid) else Bukkit.getPlayerExact(name)
    },
    private val onOwner: (Player, Runnable, Runnable) -> Boolean = { target, read, retired ->
        if (ArcScheduler.owns(target)) { read.run(); true }
        else target.scheduler.execute(plugin, read, retired, 1L)
    },
    private val offline: (String, UUID?) -> CompletableFuture<PlayerProfile> = { identity, uuid ->
        Bukkit.createProfileExact(uuid, if (uuid == null) identity else null).update()
    },
) {
    fun load(viewer: Player, identity: String): CompletableFuture<PlayerProfile> {
        val uuid = runCatching { UUID.fromString(identity) }.getOrNull()
        if (identity.equals(viewer.name, true) || uuid == viewer.uniqueId) {
            return CompletableFuture.completedFuture(viewer.playerProfile)
        }
        val online = findOnline(uuid, identity)
        if (online == null) return offline(identity, uuid)
        val result = CompletableFuture<PlayerProfile>()
        val fallback = Runnable {
            try {
                offline(identity, uuid).whenComplete { profile, error ->
                    if (error == null) result.complete(profile) else result.completeExceptionally(error)
                }
            } catch (error: Exception) {
                result.completeExceptionally(error)
            }
        }
        val read = Runnable {
            try {
                if (online.isOnline) result.complete(online.playerProfile) else fallback.run()
            } catch (error: Exception) {
                result.completeExceptionally(error)
            }
        }
        if (!onOwner(online, read, fallback)) fallback.run()
        return result
    }
}
