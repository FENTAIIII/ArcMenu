package com.fentai.arcmenu.paper.render

import com.destroystokyo.paper.profile.PlayerProfile
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CompletableFuture

class PlayerHeadProfilesTest {
    private val uuid = UUID.fromString("f1c87161-390c-49a0-9a04-a36459068b0a")
    private val profile = proxy(PlayerProfile::class.java) { _, _ -> error("Profile must be copied intact") }
    private val plugin = proxy(Plugin::class.java) { _, _ -> error("No server lookup expected") }
    private val viewer = player("Viewer", uuid) { profile }

    @Test fun `self heads use the existing skin without online lookup or networking`() {
        val service = PlayerHeadProfiles(plugin, { _, _ -> error("No lookup") }, { _, _, _ -> error("No scheduling") }, { _, _ -> error("No network") })
        assertSame(profile, service.load(viewer, "vIeWeR").getNow(null))
        assertSame(profile, service.load(viewer, uuid.toString()).getNow(null))
    }

    @Test fun `another online player's profile is read only after their owning region runs`() {
        var onOwner = false
        val target = player("Target", UUID.randomUUID()) { check(onOwner); profile }
        var pending: Runnable? = null
        val service = PlayerHeadProfiles(plugin, { _, _ -> target }, { _, task, _ -> pending = task; true }, { _, _ -> error("No network") })
        val result = service.load(viewer, "Target")
        assertFalse(result.isDone)
        onOwner = true
        pending!!.run()
        assertSame(profile, result.getNow(null))
    }

    @Test fun `offline skin lookup returns the pending async result without waiting`() {
        val lookup = CompletableFuture<PlayerProfile>()
        val service = PlayerHeadProfiles(plugin, { _, _ -> null }, { _, _, _ -> error("No scheduling") }, { name, id ->
            assertEquals("Offline", name)
            assertNull(id)
            lookup
        })
        val result = service.load(viewer, "Offline")
        assertSame(lookup, result)
        assertFalse(result.isDone)
        lookup.complete(profile)
        assertSame(profile, result.getNow(null))
    }

    @Test fun `retired online player falls back to async offline lookup`() {
        val target = player("Target", UUID.randomUUID()) { error("Retired profile must not be read") }
        var retired: Runnable? = null
        val lookup = CompletableFuture<PlayerProfile>()
        val service = PlayerHeadProfiles(plugin, { _, _ -> target }, { _, _, callback -> retired = callback; true }, { _, _ -> lookup })
        val result = service.load(viewer, "Target")
        retired!!.run()
        assertFalse(result.isDone)
        lookup.complete(profile)
        assertSame(profile, result.getNow(null))
    }

    @Test fun `late skins are delivered on the owner thread and discarded after view replacement`() {
        val pending = mutableListOf<Runnable>()
        val first = CompletableFuture<PlayerProfile>()
        var current = true
        var updates = 0
        deliverMenuHeadProfile(first, pending::add, { current }) { updates++ }
        first.complete(profile)
        assertEquals(0, updates)
        current = false
        pending.removeAt(0).run()
        assertEquals(0, updates)

        current = true
        val second = CompletableFuture<PlayerProfile>()
        deliverMenuHeadProfile(second, pending::add, { current }) { assertSame(profile, it); updates++ }
        second.complete(profile)
        pending.removeAt(0).run()
        assertEquals(1, updates)

        val failed = CompletableFuture<PlayerProfile>()
        deliverMenuHeadProfile(failed, pending::add, { true }) { error("Failed lookup cannot update the item") }
        failed.completeExceptionally(IllegalArgumentException("No skin"))
        assertTrue(pending.isEmpty())
    }

    private fun player(name: String, id: UUID, read: () -> PlayerProfile): Player = proxy(Player::class.java) { method, _ ->
        when (method) {
            "getName" -> name
            "getUniqueId" -> id
            "isOnline" -> true
            "getPlayerProfile" -> read()
            else -> error("Unexpected player access: $method")
        }
    }

    private fun <T> proxy(type: Class<T>, call: (String, Array<out Any?>?) -> Any?): T = type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> call(method.name, args) },
    )
}
