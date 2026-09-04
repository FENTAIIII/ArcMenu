package com.fentai.arcmenu.paper

import com.fentai.arcmenu.api.ArcMenuApi
import com.fentai.arcmenu.api.ArcMenuRoute
import com.fentai.arcmenu.core.config.AnimationCatalog
import com.fentai.arcmenu.core.config.MenuCatalog
import com.fentai.arcmenu.core.config.MenuSetValidator
import com.fentai.arcmenu.core.config.TemplateCatalog
import com.fentai.arcmenu.core.config.LoadResult
import com.fentai.arcmenu.paper.api.ArcMenuApiImpl
import com.fentai.arcmenu.paper.api.ApplicationRegistry
import com.fentai.arcmenu.paper.api.ExtensionRoutes
import com.fentai.arcmenu.paper.input.PointerController
import com.fentai.arcmenu.paper.input.PointerMode
import com.fentai.arcmenu.paper.input.PointerSettingsLoader
import com.fentai.arcmenu.paper.input.displayName
import com.fentai.arcmenu.paper.localization.LanguageManager
import com.fentai.arcmenu.paper.performance.PerformanceMetrics
import com.fentai.arcmenu.paper.editor.EditorService
import com.fentai.arcmenu.paper.render.BukkitMenuRenderer
import com.fentai.arcmenu.paper.render.MenuViewMode
import com.fentai.arcmenu.paper.render.TooltipStyleLoader
import com.fentai.arcmenu.paper.resource.CraftEngineBridge
import com.fentai.arcmenu.paper.resource.ResourcePackService
import com.fentai.arcmenu.paper.runtime.MenuSessions
import com.fentai.arcmenu.paper.runtime.MenuCommandRegistry
import com.fentai.arcmenu.paper.runtime.MenuShortcutSettings
import com.fentai.arcmenu.paper.runtime.MenuShortcutSettingsLoader
import com.fentai.arcmenu.paper.runtime.PlaceholderResolver
import com.fentai.arcmenu.paper.runtime.PointerButton
import com.fentai.arcmenu.paper.runtime.RuntimeScreenSettingsLoader
import com.fentai.arcmenu.paper.runtime.RuntimeStateStore
import net.kyori.adventure.text.Component
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.*
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.block.Action
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.plugin.ServicePriority
import java.nio.file.Files

class ArcMenuPlugin : JavaPlugin(), Listener {
    private var catalog = MenuCatalog()
    private var animationCatalog = AnimationCatalog()
    private val templateCatalog = TemplateCatalog()
    private lateinit var renderer: BukkitMenuRenderer
    private lateinit var sessions: MenuSessions
    private lateinit var state: RuntimeStateStore
    private lateinit var resources: ResourcePackService
    private lateinit var craftEngine: CraftEngineBridge
    private lateinit var extensionRoutes: ExtensionRoutes
    private lateinit var applicationRegistry: ApplicationRegistry
    private lateinit var api: ArcMenuApiImpl
    private lateinit var pointer: PointerController
    private lateinit var performance: PerformanceMetrics
    private lateinit var editor: EditorService
    private lateinit var language: LanguageManager
    private lateinit var menuCommands: MenuCommandRegistry
    private var shortcutSettings = MenuShortcutSettings()
    private var initialCatalogPending = false
    private val menuDirectory get() = dataFolder.toPath().resolve("menus")
    private val templateDirectory get() = dataFolder.toPath().resolve("templates")
    private val animationFile get() = dataFolder.toPath().resolve("animations.yml")

    override fun onEnable() {
        saveDefaultConfig()
        shortcutSettings = MenuShortcutSettingsLoader.load(this)
        language = LanguageManager(this).also(LanguageManager::load)
        menuCommands = MenuCommandRegistry(this, language) { player, menuId, arguments ->
            sessions.openRoot(player, menuId, arguments)
        }
        server.messenger.registerOutgoingPluginChannel(this, "BungeeCord")
        Files.createDirectories(menuDirectory)
        if (!Files.exists(menuDirectory.resolve("example.yml"))) saveResource("menus/example.yml", false)
        if (!Files.exists(menuDirectory.resolve("details.yml"))) saveResource("menus/details.yml", false)
        if (!Files.exists(menuDirectory.resolve("m4-resources.yml"))) saveResource("menus/m4-resources.yml", false)
        if (!Files.exists(menuDirectory.resolve("m5-animation.yml"))) saveResource("menus/m5-animation.yml", false)
        if (!Files.exists(animationFile)) saveResource("animations.yml", false)
        Files.createDirectories(templateDirectory)
        val templateDefaultsMarker = templateDirectory.resolve(".defaults-installed-v1")
        if (!Files.exists(templateDefaultsMarker)) {
            if (!Files.exists(templateDirectory.resolve("card.yml"))) saveResource("templates/card.yml", false)
            Files.writeString(templateDefaultsMarker, "ArcMenu bundled templates were installed once.\n")
        }
        val imageDirectory = dataFolder.toPath().resolve("images")
        Files.createDirectories(imageDirectory)
        listOf("example.png", "mouse/mouse.png", "mouse/choose.png", "ce/topaz_background.png", "ce/topaz_frame.png").forEach { path ->
            if (!Files.exists(imageDirectory.resolve(path))) saveResource("images/$path", false)
        }
        val tooltipStyles = TooltipStyleLoader.load(this)
        resources = ResourcePackService(dataFolder.toPath(), logger, language)
        try {
            resources.rebuild(tooltipStyles.nineSliceRequests())
        } catch (error: Exception) {
            logger.severe(language.log("log.resource-initial-failed", error.message.orEmpty()))
        }
        craftEngine = CraftEngineBridge(this, resources, language, this::onCraftEngineReloadReady).also(CraftEngineBridge::start)
        performance = PerformanceMetrics()
        renderer = BukkitMenuRenderer(this, resources, craftEngine, performance)
        state = RuntimeStateStore(this, language)
        val placeholders = PlaceholderResolver(this, state, language)
        pointer = PointerController(this, PointerSettingsLoader.load(this), performance::entitySpawn)
        val result = catalog.reload(menuDirectory, renderer::validate, this::validateMenuSet)
        initialCatalogPending = !result.applied
        result.errors.forEach { logger.severe(it) }
        val animationResult = if (result.applied) animationCatalog.reload(animationFile, catalog.documents) else null
        animationResult?.errors?.forEach { logger.severe(it) }
        val templates = templateCatalog.reload(templateDirectory)
        templates.errors.forEach { logger.severe(it) }
        extensionRoutes = ExtensionRoutes()
        applicationRegistry = ApplicationRegistry()
        sessions = MenuSessions(
            this, { catalog.documents }, { animationCatalog.configuration }, extensionRoutes, applicationRegistry,
            renderer, pointer, performance, placeholders, state, tooltipStyles,
            RuntimeScreenSettingsLoader.load(this), language,
        )
        if (result.applied) menuCommands.replace(catalog.documents)
        editor = EditorService(
            this, menuDirectory, { catalog.documents }, sessions, renderer, this::applyEditorCatalog,
            templateDirectory, { templateCatalog.templates }, { templateCatalog.reload(templateDirectory) }, language,
            {
                resources.sources().sorted().mapNotNull { source ->
                    resources.resolve(source)?.let { asset ->
                        com.fentai.arcmenu.protocol.EditorProtocol.ImageSnapshot(source, asset.pixelWidth, asset.pixelHeight)
                    }
                }
            },
        )
        api = ArcMenuApiImpl(sessions, extensionRoutes, applicationRegistry, pointer)
        server.servicesManager.register(ArcMenuApi::class.java, api, this, ServicePriority.Normal)
        api.registerRoute(this, "arcmenu:example-details", ArcMenuRoute { player, arguments ->
            api.open(player, "details", arguments)
        })
        server.pluginManager.registerEvents(this, this)
        if (result.applied) {
            logger.info(language.log("log.enabled", result.count, animationResult?.tracks ?: 0,
                resources.imageCount(), templates.count, pointer.settings.policy.displayName()))
        } else {
            logger.warning(language.log("log.catalog-pending"))
        }
    }

    override fun onDisable() {
        if (::menuCommands.isInitialized) menuCommands.close()
        if (::editor.isInitialized) editor.close()
        if (::api.isInitialized) api.unregisterRoutes(this)
        if (::api.isInitialized) api.unregisterApplications(this)
        server.servicesManager.unregisterAll(this)
        if (::sessions.isInitialized) sessions.close()
        if (::pointer.isInitialized) pointer.close()
        if (::craftEngine.isInitialized) craftEngine.close()
        if (::state.isInitialized) state.close()
        server.messenger.unregisterOutgoingPluginChannel(this, "BungeeCord")
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val subcommand = args.firstOrNull()?.lowercase()
        if (subcommand == "open") {
            val player = sender as? Player ?: run { reply(sender, "command.open.player-only"); return true }
            if (!player.hasPermission("arcmenu.use")) { reply(sender, "command.open.no-permission"); return true }
            val menuId = args.getOrNull(1)?.lowercase() ?: run { reply(sender, "command.open.usage"); return true }
            sessions.openRoot(player, menuId)
            return true
        }
        if (subcommand == "close") {
            if (sender is Player) {
                sessions.close(sender, true)
            } else reply(sender, "command.close.player-only")
            return true
        }
        if (subcommand == "mode" || subcommand == "mouse") {
            val player = sender as? Player ?: run { reply(sender, "command.mode.player-only"); return true }
            if (!player.hasPermission("arcmenu.use")) { reply(sender, "command.open.no-permission"); return true }
            val requested = when (args.getOrNull(1)?.lowercase()) {
                null -> {
                    reply(player, "command.mode.current", modeName(player, pointer.mode(player)), pointer.settings.policy.displayName())
                    return true
                }
                "crosshair", "touch" -> PointerMode.TOUCH
                "cursor", "mouse" -> PointerMode.MOUSE
                else -> { reply(player, "command.mode.invalid"); return true }
            }
            if (requested == PointerMode.MOUSE && !renderer.hasCursorAsset()) {
                reply(player, "command.mode.cursor-missing")
                return true
            }
            val result = pointer.setPreference(player, requested)
            if (result.accepted) sessions.refreshPointerMode(player)
            val selection = language.text(player, if (result.accepted) "pointer.selected" else "pointer.forced", modeName(player, result.mode))
            val applied = if (!result.accepted) "" else language.text(player,
                if (sessions.isRuntime(player)) "pointer.applied-current" else "pointer.applied-next")
            replyText(player, "$selection$applied${language.text(player, "common.sentence-end")}")
            return true
        }
        if (!sender.hasPermission("arcmenu.admin")) { reply(sender, "command.no-admin"); return true }
        when (subcommand) {
            "list" -> reply(sender, "command.list", catalog.documents.keys.joinToString().ifEmpty { language.text(sender, "common.none") })
            "templates" -> reply(sender, "command.templates", templateCatalog.templates.keys.joinToString().ifEmpty { language.text(sender, "common.none") })
            "animations" -> {
                val player = sender as? Player
                val ids = player?.let(sessions::animations)
                    ?: animationCatalog.configuration.menus.values.flatMap { it.tracks.keys }.toSortedSet()
                reply(sender, "command.animations", ids.joinToString().ifEmpty { language.text(sender, "common.none") })
            }
            "animate" -> {
                val player = sender as? Player ?: run { reply(sender, "command.animate.player-only"); return true }
                val id = args.getOrNull(1) ?: run { reply(sender, "command.animate.usage"); return true }
                reply(sender, if (sessions.playAnimation(player, id)) "command.animate.started" else "command.animate.unavailable", id)
            }
            "stop-animation" -> {
                val player = sender as? Player ?: run { reply(sender, "command.stop-animation.player-only"); return true }
                val id = args.getOrNull(1) ?: run { reply(sender, "command.stop-animation.usage"); return true }
                reply(sender, if (sessions.stopAnimation(player, id)) "command.stop-animation.stopped" else "command.stop-animation.unavailable", id)
            }
            "performance", "perf" -> {
                if (args.getOrNull(1).equals("reset", true)) {
                    performance.reset()
                    reply(sender, "command.performance.reset")
                } else {
                    val value = performance.snapshot()
                    reply(sender, "command.performance.report",
                        decimal(value.elapsedSeconds, 1), value.ticks, decimal(value.averageTickMicros, 2),
                        decimal(value.maxTickMicros, 2), value.matrixWrites, value.matrixSkips,
                        decimal(value.matrixSkipPercent, 1), value.metadataWrites, value.metadataSkips,
                        decimal(value.metadataSkipPercent, 1), value.entitySpawns, sessions.size, sessions.entityCount)
                }
            }
            "validate", "reload" -> {
                val reload = args[0].equals("reload", true)
                val reloadAll = reload && args.getOrNull(1).equals("all", true)
                val tooltipStyle = try {
                    TooltipStyleLoader.load(this)
                } catch (error: Exception) {
                    reply(sender, "command.reload.tooltip-invalid", error.message.orEmpty())
                    return true
                }
                val (pointerSettings, runtimeScreenSettings, nextShortcutSettings) = try {
                    reloadConfig()
                    val nextPointer = PointerSettingsLoader.load(this)
                    val nextScreen = RuntimeScreenSettingsLoader.load(this)
                    val nextShortcuts = MenuShortcutSettingsLoader.load(this)
                    language.load()
                    Triple(nextPointer, nextScreen, nextShortcuts)
                } catch (error: Exception) {
                    reply(sender, "command.reload.config-invalid", error.message.orEmpty())
                    return true
                }
                if (reloadAll) try {
                    resources.rebuild(tooltipStyle.nineSliceRequests())
                } catch (error: Exception) {
                    reply(sender, "command.reload.resource-failed", error.message.orEmpty())
                    return true
                }
                if (!reloadAll && !resources.hasNineSlices(tooltipStyle.nineSliceRequests())) {
                    reply(sender, "command.reload.tooltip-unbuilt")
                    return true
                }
                val templateCheck = if (reloadAll || !reload) TemplateCatalog().reload(templateDirectory) else null
                if (templateCheck != null && !templateCheck.applied) {
                    reply(sender, "command.reload.template-invalid")
                    templateCheck.errors.forEach { replyText(sender, it) }
                    return true
                }
                val candidateCatalog = MenuCatalog()
                val result = candidateCatalog.reload(menuDirectory, renderer::validate, this::validateMenuSet)
                val candidateAnimations = AnimationCatalog()
                val animationCheck = if (result.applied) candidateAnimations.reload(animationFile, candidateCatalog.documents) else null
                if (animationCheck != null && !animationCheck.applied) {
                    reply(sender, "command.reload.animation-invalid")
                    animationCheck.errors.forEach { replyText(sender, it) }
                    return true
                }
                if (result.applied) {
                    if (reloadAll) templateCatalog.reload(templateDirectory)
                    if (reload) {
                        menuCommands.replace(candidateCatalog.documents)
                        catalog = candidateCatalog
                        animationCatalog = candidateAnimations
                        sessions.close()
                        sessions.updateTooltipStyle(tooltipStyle)
                        sessions.updateRuntimeScreen(runtimeScreenSettings)
                        pointer.updateSettings(pointerSettings)
                        shortcutSettings = nextShortcutSettings
                    }
                    val animationCount = animationCheck?.tracks ?: 0
                    if (reload) {
                        val resourceSummary = if (reloadAll) language.text(sender, "command.reload.resources",
                            resources.imageCount(), templateCatalog.templates.size) else ""
                        val ceHint = if (reloadAll) language.text(sender, "command.reload.ce-hint") else ""
                        reply(sender, "command.reload.loaded", result.count, animationCount, resourceSummary, ceHint)
                    } else reply(sender, "command.reload.validated", result.count, animationCount,
                        templateCheck?.count ?: templateCatalog.templates.size)
                } else {
                    reply(sender, "command.reload.validation-failed")
                    result.errors.forEach { replyText(sender, it) }
                }
            }
            "resources", "resourcepack" -> {
                try {
                    val result = resources.rebuild()
                    catalog.documents.values.forEach(renderer::validate)
                    reply(sender, "command.resources.built", result.images, result.zip)
                } catch (error: Exception) {
                    reply(sender, "command.resources.failed", error.message.orEmpty())
                }
            }
            "preview" -> {
                val player = sender as? Player ?: run { reply(sender, "command.preview.player-only"); return true }
                val menu = args.getOrNull(1)?.let { catalog.documents[it]?.definition }
                    ?: run { reply(sender, "command.preview.usage"); return true }
                val mode = when (args.getOrNull(2)?.lowercase() ?: "frontend") {
                    "frontend" -> MenuViewMode.FRONTEND_PREVIEW
                    "backend" -> MenuViewMode.BACKEND_PREVIEW
                    else -> { reply(sender, "command.preview.invalid-mode"); return true }
                }
                try {
                    val count = sessions.openPreview(player, menu, mode)
                    reply(sender, "command.preview.opened", menu.id, mode.name.lowercase(), count)
                } catch (error: Exception) {
                    logger.warning(language.log("log.preview-failed", menu.id, error.message.orEmpty()))
                    reply(sender, "command.preview.failed", error.message.orEmpty())
                }
            }
            "edit", "editor" -> {
                val player = sender as? Player ?: run { reply(sender, "command.edit.player-only"); return true }
                val menuId = args.getOrNull(1)?.lowercase() ?: run { reply(sender, "command.edit.usage"); return true }
                try { replyText(sender, editor.open(player, menuId)) }
                catch (error: Exception) { reply(sender, "command.edit.failed", error.message.orEmpty()) }
            }
            "pointer" -> (sender as? Player)?.let { replyText(sender, sessions.diagnostic(it) ?: language.text(sender, "command.pointer-required")) }
                ?: reply(sender, "command.pointer-required")
            "status" -> reply(sender, "command.status", catalog.documents.size,
                animationCatalog.configuration.menus.values.sumOf { it.tracks.size }, resources.imageCount(),
                templateCatalog.templates.size, sessions.size, sessions.runtimeSize, sessions.entityCount,
                pointer.settings.policy.displayName(), com.fentai.arcmenu.protocol.EditorProtocol.VERSION)
            else -> replyText(sender, "/arcmenu open <id> | close | mode <touch|mouse> | edit <id> | animate <id> | stop-animation <id> | animations | performance [reset] | list | templates | validate | reload [all] | resources | preview <id> [frontend|backend] | pointer | status")
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val options = when {
            args.size == 1 -> buildList {
                if (sender.hasPermission("arcmenu.use")) add("open")
                if (sender.hasPermission("arcmenu.use")) add("mode")
                add("close")
                if (sender.hasPermission("arcmenu.admin")) addAll(listOf("edit", "animations", "animate", "stop-animation", "performance", "list", "templates", "validate", "reload", "resources", "preview", "pointer", "status"))
            }
            args.size == 2 && args[0].equals("open", true) -> catalog.documents.values
                .filter { it.definition.permission.isBlank() || sender.hasPermission(it.definition.permission) }
                .map { it.definition.id } + extensionRoutes.ids()
            args.size == 2 && (args[0].equals("mode", true) || args[0].equals("mouse", true)) -> listOf("touch", "mouse")
            args.size == 2 && args[0].equals("animate", true) && sender is Player -> sessions.animations(sender).toList()
            args.size == 2 && args[0].equals("stop-animation", true) && sender is Player -> sessions.animations(sender).toList()
            args.size == 2 && args[0].equals("preview", true) -> catalog.documents.keys.toList()
            args.size == 2 && (args[0].equals("edit", true) || args[0].equals("editor", true)) -> catalog.documents.keys.toList()
            args.size == 2 && args[0].equals("reload", true) -> listOf("all")
            args.size == 2 && args[0].equals("performance", true) -> listOf("reset")
            args.size == 3 && args[0].equals("preview", true) -> listOf("frontend", "backend")
            else -> emptyList()
        }
        return options.filter { it.startsWith(args.lastOrNull().orEmpty(), ignoreCase = true) }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onSwapHand(event: PlayerSwapHandItemsEvent) {
        if (!shortcutSettings.shiftF) return
        val player = event.player
        if (!player.isSneaking) return
        if (sessions.contains(player)) {
            event.isCancelled = true
            sessions.close(player, true)
            return
        }
        val mainMenuId = menuCommands.mainMenuId ?: return
        if (!player.hasPermission("arcmenu.use")) return
        event.isCancelled = true
        sessions.openRoot(player, mainMenuId)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInteract(event: PlayerInteractEvent) {
        if (!sessions.contains(event.player)) return
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action in setOf(Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK)) {
            event.isCancelled = true
            sessions.activate(event.player, PointerButton.RIGHT)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEntityInteract(event: PlayerInteractEntityEvent) {
        if (!sessions.isCapture(event.player, event.rightClicked)) return
        event.isCancelled = true
        if (event.hand == EquipmentSlot.HAND) sessions.activate(event.player, PointerButton.RIGHT)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onEntityAttack(event: EntityDamageByEntityEvent) {
        val player = event.damager as? Player ?: return
        if (!sessions.isCapture(player, event.entity)) return
        event.isCancelled = true
        sessions.activate(player, PointerButton.LEFT)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onHeldSlot(event: PlayerItemHeldEvent) {
        if (sessions.hotbarScroll(event.player, event.previousSlot, event.newSlot)) event.isCancelled = true
    }

    @Suppress("DEPRECATION")
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onChat(event: AsyncPlayerChatEvent) {
        if (!sessions.isAwaitingChat(event.player)) return
        event.isCancelled = true
        val player = event.player
        val message = event.message
        server.scheduler.runTask(this, Runnable { sessions.acceptChat(player, message) })
    }

    @EventHandler fun onQuit(event: PlayerQuitEvent) {
        if (::editor.isInitialized) editor.close(event.player)
        sessions.closeImmediately(event.player, true)
        if (::pointer.isInitialized) pointer.discard(event.player)
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        if (!sessions.isRuntime(event.player)) return
        server.scheduler.runTask(this, Runnable { sessions.reanchorRuntime(event.player) })
    }
    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) {
        if (!sessions.isRuntime(event.player)) return
        server.scheduler.runTask(this, Runnable { sessions.reanchorRuntime(event.player) })
    }

    @EventHandler fun onPluginDisable(event: PluginDisableEvent) {
        if (::api.isInitialized && event.plugin !== this) {
            api.unregisterRoutes(event.plugin)
            api.unregisterApplications(event.plugin)
        }
    }

    private fun reply(sender: CommandSender, key: String, vararg arguments: Any?) =
        replyText(sender, language.text(sender, key, *arguments))

    private fun replyText(sender: CommandSender, message: String) =
        sender.sendMessage(Component.text("[ArcMenu] $message"))

    private fun modeName(sender: CommandSender, mode: PointerMode): String =
        language.text(sender, if (mode == PointerMode.MOUSE) "mode.mouse" else "mode.touch")

    private fun decimal(value: Double, places: Int): String =
        String.format(java.util.Locale.ROOT, "%.${places}f", value)

    private fun applyEditorCatalog(): LoadResult {
        val candidate = MenuCatalog()
        val menus = candidate.reload(menuDirectory, renderer::validate, this::validateMenuSet)
        if (!menus.applied) return menus
        val animations = AnimationCatalog()
        val animationResult = animations.reload(animationFile, candidate.documents)
        if (!animationResult.applied) return LoadResult(false, catalog.documents.size, animationResult.errors)
        menuCommands.replace(candidate.documents)
        catalog = candidate
        animationCatalog = animations
        return menus
    }

    private fun validateMenuSet(documents: Map<String, com.fentai.arcmenu.core.model.MenuDocument>) {
        MenuSetValidator.validate(documents)
        menuCommands.validate(documents)
    }

    private fun onCraftEngineReloadReady(firstReload: Boolean) {
        if (!initialCatalogPending || !::renderer.isInitialized) return
        val result = applyEditorCatalog()
        if (!result.applied) {
            result.errors.forEach { logger.severe(language.log("log.craftengine-retry-error", it)) }
            return
        }
        initialCatalogPending = false
        logger.info(language.log("log.craftengine-ready",
            language.log(if (firstReload) "log.first" else "log.again"), result.count,
            animationCatalog.configuration.menus.values.sumOf { it.tracks.size }))
    }
}
