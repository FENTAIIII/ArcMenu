package com.fentai.arcmenu.paper.resource

import com.fentai.arcmenu.paper.localization.LanguageManager
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import org.bukkit.entity.Player
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import java.nio.file.Files
import java.nio.file.Path

/** Optional reflective CE boundary. ArcMenu remains loadable when CE is absent or changes binary API. */
class CraftEngineBridge(
    private val plugin: Plugin,
    private val resources: ResourcePackService,
    private val language: LanguageManager,
    private val onReloadReady: (Boolean) -> Unit = {},
) : AutoCloseable {
    private val listener = object : Listener {}
    private var eventRegistered = false
    private var reloadEventRegistered = false
    private val warned = mutableSetOf<String>()

    fun start() {
        val craftEngine = craftEngine() ?: run {
            plugin.logger.info(language.log("log.craftengine-missing"))
            return
        }
        try {
            @Suppress("UNCHECKED_CAST")
            val eventType = Class.forName(
                "net.momirealms.craftengine.bukkit.api.event.AsyncResourcePackCacheEvent", true,
                craftEngine.javaClass.classLoader,
            ).asSubclass(Event::class.java) as Class<out Event>
            plugin.server.pluginManager.registerEvent(
                eventType, listener, EventPriority.NORMAL,
                EventExecutor { _, event -> attach(event) }, plugin, true,
            )
            eventRegistered = true
            plugin.logger.info(language.log("log.craftengine-connected"))
        } catch (error: Throwable) {
            warnOnce("event", "log.craftengine-event-failed", rootMessage(error))
        }
        try {
            @Suppress("UNCHECKED_CAST")
            val eventType = Class.forName(
                "net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent", true,
                craftEngine.javaClass.classLoader,
            ).asSubclass(Event::class.java) as Class<out Event>
            plugin.server.pluginManager.registerEvent(
                eventType, listener, EventPriority.MONITOR,
                EventExecutor { _, event ->
                    val firstReload = runCatching {
                        event.javaClass.getMethod("isFirstReload").invoke(event) as Boolean
                    }.getOrDefault(false)
                    plugin.server.scheduler.runTask(plugin, Runnable {
                        if (plugin.isEnabled) onReloadReady(firstReload)
                    })
                }, plugin, true,
            )
            reloadEventRegistered = true
        } catch (error: Throwable) {
            warnOnce("reload-event", "log.craftengine-reload-event-failed", rootMessage(error))
        }
    }

    fun hasItem(id: String): Boolean = definition(id) != null

    fun buildItem(id: String, player: Player): ItemStack? {
        val definition = definition(id) ?: return null
        return try {
            val withPlayer = definition.javaClass.methods.firstOrNull {
                it.name == "buildBukkitItem" && it.parameterTypes.contentEquals(arrayOf(Player::class.java))
            }
            val noArguments = definition.javaClass.methods.firstOrNull { it.name == "buildBukkitItem" && it.parameterCount == 0 }
            (withPlayer?.invoke(definition, player) ?: noArguments?.invoke(definition)) as? ItemStack
        } catch (error: Throwable) {
            warnOnce("item:$id", "log.craftengine-item-failed", id, rootMessage(error))
            null
        }
    }

    private fun definition(id: String): Any? {
        val craftEngine = craftEngine() ?: return null
        return try {
            val api = Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineItems", true, craftEngine.javaClass.classLoader)
            api.getMethod("byId", String::class.java).invoke(null, id)
        } catch (error: Throwable) {
            warnOnce("items-api", "log.craftengine-items-api-failed", rootMessage(error))
            null
        }
    }

    private fun attach(event: Event) {
        var pack: Path? = null
        try {
            pack = resources.rebuild().zip
        } catch (error: Throwable) {
            warnOnce("pack-build", "log.craftengine-pack-build-failed", rootMessage(error))
            if (Files.isRegularFile(resources.zipPath)) pack = resources.zipPath
        }
        if (pack == null) return
        try {
            val externalPack = pack.toAbsolutePath().normalize()
            require(Files.isRegularFile(externalPack)) { "ArcMenu 资源包文件不存在: $externalPack" }
            val route = registerExternalPack(event, externalPack)
            plugin.logger.info(language.log("log.craftengine-pack-registered", externalPack, route))
        } catch (error: Throwable) {
            // This is tied to a CE rebuild operation. Report every failure so a later
            // /ce reload all cannot appear successful merely because the first warning
            // in this server process was already emitted.
            plugin.logger.warning(language.log("log.craftengine-pack-rejected", rootMessage(error)))
        }
    }

    private fun craftEngine(): Plugin? = plugin.server.pluginManager.getPlugin("CraftEngine")?.takeIf(Plugin::isEnabled)

    private fun warnOnce(key: String, messageKey: String, vararg arguments: Any?) {
        synchronized(warned) { if (warned.add(key)) plugin.logger.warning(language.log(messageKey, *arguments)) }
    }

    private fun rootMessage(error: Throwable): String {
        var current = error
        while (current.cause != null) current = current.cause!!
        return current.message ?: current.javaClass.simpleName
    }

    override fun close() {
        if (eventRegistered || reloadEventRegistered) HandlerList.unregisterAll(listener)
        eventRegistered = false
        reloadEventRegistered = false
    }
}

/**
 * CraftEngine 26.6.3's registerExternalResourcePack implementation checks
 * Path.getFileName().endsWith(".zip"), which rejects a normal file name such
 * as arcmenu-resourcepack.zip. Prefer the same cache set used by BetterHud and
 * retain the convenience method only as a fallback for another CE API shape.
 */
internal fun registerExternalPack(event: Any, externalPack: Path): String {
    val cacheGetter = event.javaClass.methods.firstOrNull { it.name == "cacheData" && it.parameterCount == 0 }
    if (cacheGetter != null) {
        val cache = cacheGetter.invoke(event)
        val zipsGetter = cache.javaClass.methods.firstOrNull { it.name == "externalZips" && it.parameterCount == 0 }
        if (zipsGetter != null) {
            @Suppress("UNCHECKED_CAST")
            val zips = zipsGetter.invoke(cache) as MutableSet<Path>
            zips.add(externalPack)
            return "cacheData.externalZips"
        }
    }
    val direct = event.javaClass.methods.firstOrNull {
        it.name == "registerExternalResourcePack" && it.parameterTypes.contentEquals(arrayOf(Path::class.java))
    } ?: throw NoSuchMethodException("CraftEngine 事件没有外部资源包注册 API")
    direct.invoke(event, externalPack)
    return "registerExternalResourcePack"
}
