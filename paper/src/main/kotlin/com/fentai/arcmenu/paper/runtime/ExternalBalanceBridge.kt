package com.fentai.arcmenu.paper.runtime

import com.fentai.arcmenu.core.behavior.BalanceKind
import com.fentai.arcmenu.core.behavior.BalanceOperation
import com.fentai.arcmenu.paper.localization.LanguageManager
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID

/** Optional reflection bridges keep Vault and PlayerPoints out of ArcMenu's hard dependencies. */
class ExternalBalanceBridge(private val plugin: Plugin, private val language: LanguageManager) {
    private val warnings = mutableSetOf<BalanceKind>()

    fun apply(player: Player, kind: BalanceKind, operation: BalanceOperation, amount: Double): Boolean = when (kind) {
        BalanceKind.MONEY -> vault(player, operation, amount)
        BalanceKind.POINTS -> playerPoints(player, operation, amount.toInt())
    }.also { success ->
        if (!success && warnings.add(kind)) plugin.logger.warning(
            language.log(
                "log.balance-provider-missing",
                if (kind == BalanceKind.MONEY) "Vault Economy provider" else "PlayerPoints",
            ),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun vault(player: OfflinePlayer, operation: BalanceOperation, amount: Double): Boolean = runCatching {
        val economyClass = Class.forName("net.milkbowl.vault.economy.Economy") as Class<Any>
        val registration = Bukkit.getServicesManager().getRegistration(economyClass) ?: return false
        val provider = registration.provider
        fun invoke(name: String, value: Double): Any? {
            val method = provider.javaClass.methods.firstOrNull {
                it.name == name && it.parameterCount == 2 && it.parameterTypes[0].isAssignableFrom(player.javaClass)
            } ?: return null
            return method.invoke(provider, player, value)
        }
        when (operation) {
            BalanceOperation.ADD -> invoke("depositPlayer", amount) ?: return false
            BalanceOperation.TAKE -> invoke("withdrawPlayer", amount) ?: return false
            BalanceOperation.SET -> {
                val getter = provider.javaClass.methods.firstOrNull {
                    it.name == "getBalance" && it.parameterCount == 1 && it.parameterTypes[0].isAssignableFrom(player.javaClass)
                } ?: return false
                val current = (getter.invoke(provider, player) as? Number)?.toDouble() ?: return false
                val delta = amount - current
                if (delta >= 0) invoke("depositPlayer", delta) ?: return false else invoke("withdrawPlayer", -delta) ?: return false
            }
        }
        true
    }.getOrDefault(false)

    private fun playerPoints(player: Player, operation: BalanceOperation, amount: Int): Boolean = runCatching {
        val pointsPlugin = Bukkit.getPluginManager().getPlugin("PlayerPoints")?.takeIf(Plugin::isEnabled) ?: return false
        val api = pointsPlugin.javaClass.methods.firstOrNull { it.name.equals("getAPI", true) && it.parameterCount == 0 }
            ?.invoke(pointsPlugin) ?: return false
        val methodName = when (operation) {
            BalanceOperation.ADD -> "give"
            BalanceOperation.TAKE -> "take"
            BalanceOperation.SET -> "set"
        }
        val method = api.javaClass.methods.firstOrNull {
            it.name == methodName && it.parameterCount == 2 && it.parameterTypes[0] == UUID::class.java
        } ?: return false
        method.invoke(api, player.uniqueId, amount)
        true
    }.getOrDefault(false)
}
