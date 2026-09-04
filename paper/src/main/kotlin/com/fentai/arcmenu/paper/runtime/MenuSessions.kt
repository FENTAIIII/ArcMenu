package com.fentai.arcmenu.paper.runtime

import com.fentai.arcmenu.api.*
import com.fentai.arcmenu.core.animation.*
import com.fentai.arcmenu.core.behavior.*
import com.fentai.arcmenu.core.geometry.HitTester
import com.fentai.arcmenu.core.geometry.MenuPlane
import com.fentai.arcmenu.core.geometry.Transforms
import com.fentai.arcmenu.core.model.*
import com.fentai.arcmenu.paper.input.PointerController
import com.fentai.arcmenu.paper.input.PointerMode
import com.fentai.arcmenu.paper.input.networkCameraAngle
import com.fentai.arcmenu.paper.localization.LanguageManager
import com.fentai.arcmenu.paper.api.ExtensionRoutes
import com.fentai.arcmenu.paper.api.ApplicationRegistry
import com.fentai.arcmenu.paper.api.RegisteredApplication
import com.fentai.arcmenu.paper.render.BukkitMenuRenderer
import com.fentai.arcmenu.paper.render.MenuViewMode
import com.fentai.arcmenu.paper.render.TooltipStyles
import com.fentai.arcmenu.paper.performance.PerformanceMetrics
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

class MenuSessions(
    private val plugin: Plugin,
    private val menus: () -> Map<String, MenuDocument>,
    private val animations: () -> AnimationConfiguration,
    private val extensionRoutes: ExtensionRoutes,
    private val applicationRegistry: ApplicationRegistry,
    private val renderer: BukkitMenuRenderer,
    private val pointer: PointerController,
    private val performance: PerformanceMetrics,
    private val placeholders: PlaceholderResolver,
    private val state: RuntimeStateStore,
    private var tooltipStyles: TooltipStyles,
    private var runtimeScreen: RuntimeScreenSettings,
    private val language: LanguageManager,
) : RuntimeControl, AutoCloseable {
    private sealed interface Route { val arguments: List<String> }
    private data class MenuRoute(val menuId: String, override val arguments: List<String>) : Route
    private data class ApplicationRoute(val applicationId: String, override val arguments: List<String>) : Route
    private data class Session(
        val player: Player,
        var menu: MenuDefinition,
        var plane: MenuPlane,
        val mode: MenuViewMode,
        var view: BukkitMenuRenderer.MenuView,
        var hitTester: HitTester,
        var worldId: UUID,
        var openedAt: Vec3,
        var capture: Interaction,
        val textNodes: Map<String, TextNode>,
        val imageNodes: Map<String, ImageNode>,
        val timeline: AnimationTimeline?,
        var pointerMode: PointerMode,
        var lastAnimation: AnimationSnapshot,
        var transitionActive: Boolean,
        var closing: Boolean = false,
        var closeEventRun: Boolean = false,
        var ticks: Long = 0,
        var lastPoint: MenuPoint? = null,
        var lastRegion: InteractionRegion? = null,
        var cursorChoosing: Boolean = false,
    )

    private data class Placement(val plane: MenuPlane, val worldId: UUID, val openedAt: Vec3)
    private data class CatcherInput(val owner: Session, val catcher: CatcherAction, val stage: Int)

    private inner class HostedApplication(
        val registration: RegisteredApplication,
        val player: Player,
        val arguments: List<String>,
        val designCanvas: Canvas,
        var plane: MenuPlane,
        var worldId: UUID,
        var openedAt: Vec3,
        var capture: Interaction,
        var overlay: BukkitMenuRenderer.MenuView,
        var pointerMode: PointerMode,
    ) {
        lateinit var context: ApplicationContext
        var callback: ArcMenuApplicationSession? = null
        var ticks: Long = 0
        var lastPoint: MenuPoint? = null
        var cursorInteractive = false
        @Volatile var active = true
        val trackedEntities = linkedSetOf<Entity>()
        val trackedTasks = linkedSetOf<BukkitTask>()
        val trackedResources = mutableListOf<AutoCloseable>()

        fun cleanupSurfaceEntities() {
            trackedEntities.toList().forEach { runCatching { if (it.isValid) it.remove() } }
            trackedEntities.clear()
        }

        fun cleanupResources() {
            synchronized(trackedTasks) {
                trackedTasks.toList().forEach { runCatching { it.cancel() } }
                trackedTasks.clear()
            }
            cleanupSurfaceEntities()
            trackedResources.asReversed().forEach { resource ->
                runCatching { resource.close() }.onFailure {
                    plugin.logger.warning(language.log("log.app-resource-cleanup-failed", registration.id, it.message.orEmpty()))
                }
            }
            trackedResources.clear()
        }
    }

    private inner class ApplicationContext(private val runtime: HostedApplication) : ArcMenuApplicationContext {
        override val player: Player get() = runtime.player
        override val applicationId: String get() = runtime.registration.id
        override val arguments: List<String> get() = runtime.arguments.toList()
        override val owner: Plugin get() = runtime.registration.owner

        override fun surface(): ArcMenuSurface {
            mainThread()
            return apiSurface(runtime.worldId, runtime.plane)
        }

        override fun pointer(): ArcMenuPoint? {
            mainThread()
            return pointer.sample(runtime.player, runtime.plane).api()
        }

        override fun pointerMode(): ArcMenuPointerMode {
            mainThread()
            return runtime.pointerMode.api()
        }
        override fun isActive(): Boolean = runtime.active

        override fun setCursorInteractive(interactive: Boolean) {
            mainThread()
            if (!isActive() || runtime.cursorInteractive == interactive) return
            runtime.cursorInteractive = interactive
            if (runtime.pointerMode == PointerMode.MOUSE) {
                pointer.sample(runtime.player, runtime.plane)?.let {
                    runtime.overlay.updateCursor(it, pointer.settings.cursorStyle, interactive)
                }
            }
        }

        override fun <T : Entity> spawnPrivate(location: Location, type: Class<T>, initializer: java.util.function.Consumer<T>): T {
            mainThread()
            require(isActive()) { "应用会话已经关闭" }
            val world = requireNotNull(location.world) { "私有实体位置必须包含世界" }
            require(world.uid == runtime.worldId) { "私有实体必须创建在当前应用屏幕所在世界" }
            val entity = world.spawn(location, type) {
                it.isVisibleByDefault = false
                it.isPersistent = false
                initializer.accept(it)
            }
            runtime.player.showEntity(plugin, entity)
            runtime.trackedEntities += entity
            return entity
        }

        override fun <T : Entity> track(entity: T): T {
            mainThread()
            require(isActive()) { "应用会话已经关闭" }
            runtime.trackedEntities += entity
            return entity
        }

        override fun <T : BukkitTask> track(task: T): T {
            mainThread()
            require(isActive()) { "应用会话已经关闭" }
            synchronized(runtime.trackedTasks) { runtime.trackedTasks += task }
            return task
        }

        override fun <T : AutoCloseable> track(resource: T): T {
            mainThread()
            require(isActive()) { "应用会话已经关闭" }
            runtime.trackedResources += resource
            return resource
        }

        override fun execute(task: Runnable): Boolean {
            if (!isActive()) return false
            if (Bukkit.isPrimaryThread()) {
                if (!isActive()) return false
                task.run()
            } else {
                Bukkit.getScheduler().runTask(plugin, Runnable { if (isActive()) task.run() })
            }
            return true
        }

        override fun runLater(delayTicks: Long, task: Runnable): BukkitTask {
            mainThread()
            require(isActive()) { "应用会话已经关闭" }
            val scheduled = Bukkit.getScheduler().runTaskLater(
                plugin,
                Runnable { if (isActive()) task.run() },
                delayTicks.coerceAtLeast(1L),
            )
            synchronized(runtime.trackedTasks) { runtime.trackedTasks += scheduled }
            return scheduled
        }

        override fun runTimer(delayTicks: Long, periodTicks: Long, task: Runnable): BukkitTask {
            mainThread()
            require(isActive()) { "应用会话已经关闭" }
            val scheduled = Bukkit.getScheduler().runTaskTimer(
                plugin,
                Runnable { if (isActive()) task.run() },
                delayTicks.coerceAtLeast(1L),
                periodTicks.coerceAtLeast(1L),
            )
            synchronized(runtime.trackedTasks) { runtime.trackedTasks += scheduled }
            return scheduled
        }

        override fun openMenu(menuId: String, arguments: List<String>): Boolean {
            mainThread()
            return isActive() && open(runtime.player, menuId.lowercase(), remember = true, arguments = arguments.toList())
        }

        override fun openApplication(applicationId: String, arguments: List<String>): Boolean {
            mainThread()
            return isActive() && openApplication(runtime.player, applicationId.lowercase(), remember = true, arguments = arguments.toList())
        }

        override fun back(): Boolean {
            mainThread()
            return isActive() && this@MenuSessions.back(runtime.player)
        }

        override fun close() {
            mainThread()
            if (isActive()) this@MenuSessions.close(runtime.player, true)
        }

        private fun mainThread() {
            check(Bukkit.isPrimaryThread()) { "ArcMenu 应用上下文必须在服务端主线程调用；异步结果请使用 execute" }
        }
    }

    private val executor = ActionExecutor(plugin, placeholders, state, language)
    private val sessions = mutableMapOf<UUID, Session>()
    private val applicationSessions = mutableMapOf<UUID, HostedApplication>()
    private val history = mutableMapOf<UUID, ArrayDeque<Route>>()
    private val lastActivation = mutableMapOf<UUID, Long>()
    private val routeDepth = mutableMapOf<UUID, Int>()
    private val routePlacement = mutableMapOf<UUID, Placement>()
    private val catcherInputs = mutableMapOf<UUID, CatcherInput>()
    private val retypeRequests = mutableSetOf<UUID>()
    private val awaitingChats = ConcurrentHashMap.newKeySet<UUID>()
    private val captureKey = NamespacedKey(plugin, "menu-capture-owner")
    private var ticker: BukkitTask? = null

    val size: Int get() = sessions.size + applicationSessions.size
    val runtimeSize: Int get() = sessions.values.count { it.mode == MenuViewMode.RUNTIME } + applicationSessions.size
    val entityCount: Int get() = sessions.values.sumOf {
        it.view.entityCount + 1 + if (it.pointerMode == PointerMode.MOUSE) 1 else 0
    } + applicationSessions.values.sumOf {
        it.overlay.entityCount + it.trackedEntities.size + 1 + if (it.pointerMode == PointerMode.MOUSE) 1 else 0
    }

    fun contains(player: Player) = sessions.containsKey(player.uniqueId) || applicationSessions.containsKey(player.uniqueId)
    fun isRuntime(player: Player) = sessions[player.uniqueId]?.mode == MenuViewMode.RUNTIME || applicationSessions.containsKey(player.uniqueId)

    fun activeApplication(player: Player): String? = applicationSessions[player.uniqueId]?.registration?.id

    fun surface(player: Player): ArcMenuSurface? = applicationSessions[player.uniqueId]?.context?.surface()
        ?: sessions[player.uniqueId]?.let { apiSurface(it.worldId, it.plane) }

    fun pointerPoint(player: Player): ArcMenuPoint? {
        val plane = applicationSessions[player.uniqueId]?.plane ?: sessions[player.uniqueId]?.plane ?: return null
        return pointer.sample(player, plane).api()
    }

    fun updateTooltipStyle(styles: TooltipStyles) {
        tooltipStyles = styles
    }

    fun updateRuntimeScreen(settings: RuntimeScreenSettings) {
        runtimeScreen = settings
    }

    fun openPreview(player: Player, menu: MenuDefinition, mode: MenuViewMode): Int {
        require(mode != MenuViewMode.RUNTIME) { "运行菜单必须通过 open" }
        history.remove(player.uniqueId)
        removeApplication(player, ArcMenuApplicationCloseReason.REPLACED)
        removeSession(player, runCloseEvent = false)
        return createSession(player, menu, mode).let { it.view.entityCount + 1 }
    }

    /** Rebuilds an editor preview on the original first-open plane, even after the player's view changes. */
    fun updatePreview(player: Player, menu: MenuDefinition, mode: MenuViewMode): Int {
        require(mode != MenuViewMode.RUNTIME) { "编辑预览不能进入运行模式" }
        val previous = sessions[player.uniqueId]?.takeIf { it.mode != MenuViewMode.RUNTIME }
            ?: throw IllegalArgumentException("编辑预览已关闭")
        val placement = Placement(previous.plane.withCanvas(menu.canvas), previous.worldId, previous.openedAt)
        removeSession(player, runCloseEvent = false, preservePointer = true)
        return createSession(player, menu, mode, placement).let { it.view.entityCount + 1 }
    }

    /** Updates live editor movement on the existing Display entities and preserves the original plane. */
    fun updatePreviewGeometry(player: Player, menu: MenuDefinition, mode: MenuViewMode): Int {
        require(mode != MenuViewMode.RUNTIME) { "编辑预览不能进入运行模式" }
        val session = sessions[player.uniqueId]?.takeIf { it.mode != MenuViewMode.RUNTIME }
            ?: throw IllegalArgumentException("编辑预览已关闭")
        require(session.mode == mode) { "编辑器 Tab 已变化，需要重建预览" }
        require(session.menu.canvas == menu.canvas) { "画布已变化，需要重建预览" }
        session.view.updateEditorGeometry(menu, mode)
        session.menu = menu
        session.hitTester = HitTester(menu.backend)
        session.lastRegion = null
        return session.view.entityCount + 1
    }

    fun markEditorPoint(player: Player, point: MenuPoint) {
        sessions[player.uniqueId]?.takeIf { it.mode != MenuViewMode.RUNTIME }?.view?.mark(point)
    }

    fun openRoot(player: Player, menuId: String, arguments: List<String> = emptyList()): Boolean {
        history.remove(player.uniqueId)
        if (arguments.isEmpty()) state.clearArguments(player) else state.setArguments(player, arguments)
        return open(player, menuId, remember = false, arguments = arguments)
    }

    override fun open(player: Player, menuId: String, remember: Boolean, arguments: List<String>): Boolean {
        val depth = routeDepth.getOrDefault(player.uniqueId, 0)
        if (depth >= MAX_ROUTE_DEPTH) {
            message(player, "runtime.route-depth", MAX_ROUTE_DEPTH)
            return false
        }
        routeDepth[player.uniqueId] = depth + 1
        return try {
            openInternal(player, menuId, remember, arguments)
        } finally {
            if (depth == 0) {
                routeDepth.remove(player.uniqueId)
                routePlacement.remove(player.uniqueId)
            } else routeDepth[player.uniqueId] = depth
        }
    }

    private fun openInternal(player: Player, menuId: String, remember: Boolean, arguments: List<String>): Boolean {
        val menu = menus()[menuId]?.definition ?: run {
            try {
                extensionRoutes.dispatch(player, menuId, arguments)?.let { return it }
            } catch (error: Exception) {
                plugin.logger.warning(language.log("log.route-failed", menuId, error.message.orEmpty()))
                message(player, "runtime.route-failed", error.message.orEmpty())
                return false
            }
            message(player, "runtime.menu-missing", menuId)
            return false
        }
        if (!player.hasPermission("arcmenu.use") || (menu.permission.isNotBlank() && !player.hasPermission(menu.permission))) {
            message(player, "runtime.menu-no-permission")
            return false
        }

        val previous = sessions[player.uniqueId]
        val previousApplication = applicationSessions[player.uniqueId]
        val transitionKind = if (previous?.mode == MenuViewMode.RUNTIME || previousApplication != null) TransitionKind.SWITCH else TransitionKind.ENTER
        val placement = previous?.takeIf { it.mode == MenuViewMode.RUNTIME }?.let {
            Placement(it.plane.withCanvas(menu.canvas), it.worldId, it.openedAt)
        } ?: previousApplication?.let {
            Placement(it.plane.withCanvas(menu.canvas), it.worldId, it.openedAt)
        } ?: routePlacement[player.uniqueId]?.let { it.copy(plane = it.plane.withCanvas(menu.canvas)) }
        if (placement != null) routePlacement[player.uniqueId] = placement

        if (remember && previous?.mode == MenuViewMode.RUNTIME && previous.menu.id != menu.id) {
            history.computeIfAbsent(player.uniqueId) { ArrayDeque() }.push(MenuRoute(previous.menu.id, state.arguments(player)))
        } else if (remember && previousApplication != null) {
            history.computeIfAbsent(player.uniqueId) { ArrayDeque() }
                .push(ApplicationRoute(previousApplication.registration.id, previousApplication.arguments))
        }
        val closeResult = removeSession(
            player,
            runCloseEvent = previous?.mode == MenuViewMode.RUNTIME,
            preservePointer = previous?.mode == MenuViewMode.RUNTIME,
        )
        if (closeResult == ActionResult.SESSION_CHANGED) return true
        if (previousApplication != null) {
            removeApplication(player, ArcMenuApplicationCloseReason.REPLACED, preservePointer = true)
        }
        return try {
            state.setArguments(player, arguments)
            val created = createSession(player, menu, MenuViewMode.RUNTIME, placement, transitionKind)
            when (executor.run(menu.events.open, player, this)) {
                ActionResult.RETURN -> {
                    if (sessions[player.uniqueId] === created) removeSession(player, runCloseEvent = false)
                    false
                }
                else -> true
            }
        } catch (error: Exception) {
            plugin.logger.warning(language.log("log.menu-open-failed", menuId, error.message.orEmpty()))
            message(player, "runtime.menu-open-failed", error.message.orEmpty())
            false
        }
    }

    override fun openApplication(
        player: Player,
        applicationId: String,
        remember: Boolean,
        arguments: List<String>,
    ): Boolean {
        val depth = routeDepth.getOrDefault(player.uniqueId, 0)
        if (depth >= MAX_ROUTE_DEPTH) {
            message(player, "runtime.app-route-depth", MAX_ROUTE_DEPTH)
            return false
        }
        routeDepth[player.uniqueId] = depth + 1
        return try {
            openApplicationInternal(player, applicationId, remember, arguments)
        } finally {
            if (depth == 0) {
                routeDepth.remove(player.uniqueId)
                routePlacement.remove(player.uniqueId)
            } else routeDepth[player.uniqueId] = depth
        }
    }

    private fun openApplicationInternal(
        player: Player,
        applicationId: String,
        remember: Boolean,
        arguments: List<String>,
    ): Boolean {
        val registration = applicationRegistry.get(applicationId) ?: run {
            message(player, "runtime.app-missing", applicationId)
            return false
        }
        if (!registration.owner.isEnabled) {
            message(player, "runtime.app-owner-unavailable", applicationId)
            return false
        }
        if (!player.hasPermission("arcmenu.use") ||
            (registration.options.permission.isNotBlank() && !player.hasPermission(registration.options.permission))) {
            message(player, "runtime.app-no-permission")
            return false
        }

        val previous = sessions[player.uniqueId]
        val previousApplication = applicationSessions[player.uniqueId]
        val inheritedDesignCanvas = when {
            !registration.options.inheritCanvas -> null
            previous?.mode == MenuViewMode.RUNTIME -> previous.menu.canvas
            previousApplication != null -> previousApplication.designCanvas
            else -> null
        }
        val designCanvas = inheritedDesignCanvas ?: registration.options.canvas.core()
        val inheritedPlacement = previous?.takeIf { it.mode == MenuViewMode.RUNTIME }?.let {
            Placement(it.plane, it.worldId, it.openedAt)
        } ?: previousApplication?.let {
            Placement(it.plane, it.worldId, it.openedAt)
        } ?: routePlacement[player.uniqueId]
        if (inheritedPlacement != null) routePlacement[player.uniqueId] = inheritedPlacement

        if (remember && previous?.mode == MenuViewMode.RUNTIME) {
            history.computeIfAbsent(player.uniqueId) { ArrayDeque() }
                .push(MenuRoute(previous.menu.id, state.arguments(player)))
        } else if (remember && previousApplication != null && previousApplication.registration.id != registration.id) {
            history.computeIfAbsent(player.uniqueId) { ArrayDeque() }
                .push(ApplicationRoute(previousApplication.registration.id, previousApplication.arguments))
        }

        val closeResult = removeSession(
            player,
            runCloseEvent = previous?.mode == MenuViewMode.RUNTIME,
            preservePointer = previous?.mode == MenuViewMode.RUNTIME,
        )
        if (closeResult == ActionResult.SESSION_CHANGED) return true
        if (previousApplication != null) {
            removeApplication(player, ArcMenuApplicationCloseReason.REPLACED, preservePointer = true)
        }

        return createApplication(player, registration, arguments.toList(), designCanvas, inheritedPlacement)
    }

    override fun close(player: Player, clearHistory: Boolean) {
        val session = sessions[player.uniqueId]
        val application = applicationSessions[player.uniqueId]
        if (application != null) removeApplication(player, ArcMenuApplicationCloseReason.REQUESTED)
        else if (session != null && session.mode == MenuViewMode.RUNTIME && player.isOnline) beginClose(session)
        else removeSession(player, runCloseEvent = session?.mode == MenuViewMode.RUNTIME)
        if (clearHistory) history.remove(player.uniqueId)
        if (clearHistory) state.clearArguments(player)
        lastActivation.remove(player.uniqueId)
    }

    /** Explicit terminal lifecycle cleanup (quit and plugin shutdown). */
    fun closeImmediately(player: Player, clearHistory: Boolean = true) {
        removeApplication(player, ArcMenuApplicationCloseReason.PLAYER_QUIT)
        removeSession(player, runCloseEvent = sessions[player.uniqueId]?.mode == MenuViewMode.RUNTIME)
        // A close event may route to another menu. A terminal lifecycle event
        // must not leave that replacement alive after the player has gone.
        removeSession(player, runCloseEvent = false)
        if (clearHistory) history.remove(player.uniqueId)
        if (clearHistory) state.clearArguments(player)
        lastActivation.remove(player.uniqueId)
    }

    override fun playAnimation(player: Player, animationId: String): Boolean {
        val session = sessions[player.uniqueId]?.takeIf { it.mode == MenuViewMode.RUNTIME && !it.closing } ?: return false
        val timeline = session.timeline ?: return false
        if (!timeline.play(animationId)) return false
        applyAnimation(session, timeline.initial())
        return true
    }

    override fun stopAnimation(player: Player, animationId: String): Boolean {
        val session = sessions[player.uniqueId]?.takeIf { it.mode == MenuViewMode.RUNTIME && !it.closing } ?: return false
        val timeline = session.timeline ?: return false
        if (!timeline.stop(animationId)) return false
        applyAnimation(session, timeline.initial())
        return true
    }

    fun animations(player: Player): Set<String> = sessions[player.uniqueId]?.timeline?.trackIds() ?: emptySet()

    fun refreshPointerMode(player: Player): PointerMode? {
        val application = applicationSessions[player.uniqueId]
        if (application != null) {
            val previous = application.pointerMode
            val requested = pointer.mode(player)
            if (requested != previous) {
                pointer.end(player)
                application.pointerMode = requested
                if (reanchorApplication(application)) {
                    invokeApplication(application, "pointer-mode") {
                        application.callback?.onPointerModeChanged(ArcMenuPointerModeEvent(previous.api(), requested.api()))
                    }
                }
                return requested
            }
            pointer.end(player)
            val mode = pointer.begin(player, application.plane)
            application.pointerMode = mode
            application.overlay.configureCursor(pointer.settings.cursorStyle.takeIf { mode == PointerMode.MOUSE })
            application.lastPoint = pointer.sample(player, application.plane)
            application.lastPoint?.let { point ->
                if (mode == PointerMode.MOUSE) application.overlay.updateCursor(
                    point,
                    pointer.settings.cursorStyle,
                    application.cursorInteractive,
                )
            }
            return mode
        }
        val session = sessions[player.uniqueId]?.takeIf { it.mode == MenuViewMode.RUNTIME && !it.closing } ?: return null
        val requested = pointer.mode(player)
        if (requested != session.pointerMode) {
            pointer.end(player)
            session.pointerMode = requested
            reanchorRuntime(player)
            return requested
        }
        pointer.end(player)
        val mode = pointer.begin(player, session.plane)
        session.pointerMode = mode
        val cursor = pointer.settings.cursorStyle.takeIf { mode == PointerMode.MOUSE }
        session.view.configureCursor(cursor)
        session.cursorChoosing = false
        session.view.updateTooltipStyle(tooltipStyles.forMouse(mode == PointerMode.MOUSE))
        pointer.sample(player, session.plane)?.let { point ->
            if (cursor != null) session.view.updateCursor(point, cursor)
            session.lastPoint = point
            session.lastRegion = null
        }
        session.view.hideTooltip()
        return mode
    }

    /**
     * Keeps a runtime menu logically open through teleport, dimension transfer,
     * and respawn by replacing only its world entities and camera anchor. Menu
     * open/close actions and animation state are not restarted.
     */
    fun reanchorRuntime(player: Player) {
        applicationSessions[player.uniqueId]?.let {
            reanchorApplication(it)
            return
        }
        val session = sessions[player.uniqueId]?.takeIf { it.mode == MenuViewMode.RUNTIME } ?: return
        if (!player.isOnline || player.isDead) return
        val nextPlane = plane(
            player,
            session.menu.canvas.forRuntimeScreen(runtimeScreen.forMode(session.pointerMode)),
            session.pointerMode,
        )
        val cursorStyle = pointer.settings.cursorStyle.takeIf { session.pointerMode == PointerMode.MOUSE }
        var nextView: BukkitMenuRenderer.MenuView? = null
        var nextCapture: Interaction? = null
        try {
            val createdView = renderer.create(
                player, session.menu, nextPlane, session.mode,
                resolve = { placeholders.expand(player, it) },
                tooltipStyle = tooltipStyles.forMouse(session.pointerMode == PointerMode.MOUSE),
                cursorStyle = cursorStyle,
                initialAnimation = session.lastAnimation,
            )
            nextView = createdView
            val createdCapture = capture(player, nextPlane)
            nextCapture = createdCapture
            if (session.pointerMode == PointerMode.MOUSE) {
                if (pointer.isMouseActive(player)) pointer.reanchor(player, nextPlane) else pointer.begin(player, nextPlane)
            } else pointer.end(player)

            val previousView = session.view
            val previousCapture = session.capture
            session.plane = nextPlane
            session.view = createdView
            session.capture = createdCapture
            session.worldId = player.world.uid
            session.openedAt = vec(player.location.toVector())
            session.lastPoint = pointer.sample(player, nextPlane)
            session.lastRegion = null
            session.cursorChoosing = false
            cursorStyle?.let { style -> session.lastPoint?.let { createdView.updateCursor(it, style) } }
            previousCapture.remove()
            previousView.close()
        } catch (error: Exception) {
            nextCapture?.remove()
            nextView?.close()
            plugin.logger.warning(language.log("log.menu-reanchor-failed", player.name, error.message.orEmpty()))
        }
    }

    private fun reanchorApplication(runtime: HostedApplication): Boolean {
        val player = runtime.player
        if (!player.isOnline || player.isDead || applicationSessions[player.uniqueId] !== runtime) return false
        val canvas = runtime.designCanvas.forRuntimeScreen(runtimeScreen.forMode(runtime.pointerMode))
        val nextPlane = plane(player, canvas, runtime.pointerMode)
        val cursorStyle = pointer.settings.cursorStyle.takeIf { runtime.pointerMode == PointerMode.MOUSE }
        var nextOverlay: BukkitMenuRenderer.MenuView? = null
        var nextCapture: Interaction? = null
        return try {
            val createdOverlay = renderer.create(
                player,
                emptyApplicationMenu(canvas),
                nextPlane,
                MenuViewMode.RUNTIME,
                tooltipStyle = null,
                cursorStyle = cursorStyle,
            )
            nextOverlay = createdOverlay
            val createdCapture = capture(player, nextPlane)
            nextCapture = createdCapture
            if (runtime.pointerMode == PointerMode.MOUSE) {
                if (pointer.isMouseActive(player)) pointer.reanchor(player, nextPlane) else pointer.begin(player, nextPlane)
            } else pointer.end(player)

            val previousOverlay = runtime.overlay
            val previousCapture = runtime.capture
            runtime.cleanupSurfaceEntities()
            runtime.plane = nextPlane
            runtime.overlay = createdOverlay
            runtime.capture = createdCapture
            runtime.worldId = player.world.uid
            runtime.openedAt = vec(player.location.toVector())
            runtime.lastPoint = pointer.sample(player, nextPlane)
            runtime.lastPoint?.let { point ->
                cursorStyle?.let { createdOverlay.updateCursor(point, it, runtime.cursorInteractive) }
            }
            previousCapture.remove()
            previousOverlay.close()
            invokeApplication(runtime, "surface") {
                runtime.callback?.onSurfaceChanged(ArcMenuSurfaceEvent(runtime.context.surface(), false))
            }
        } catch (error: Throwable) {
            nextCapture?.remove()
            nextOverlay?.close()
            reportApplicationFailure(player, runtime.registration.id, "surface", error)
            false
        }
    }

    override fun back(player: Player): Boolean {
        val stack = history[player.uniqueId]
        val target = stack?.poll()
        if (target == null) {
            applicationSessions[player.uniqueId]?.let {
                removeApplication(player, ArcMenuApplicationCloseReason.BACK)
            } ?: close(player, true)
            return false
        }
        if (stack.isEmpty()) history.remove(player.uniqueId)
        val application = applicationSessions[player.uniqueId]
        if (application != null) {
            routePlacement[player.uniqueId] = Placement(application.plane, application.worldId, application.openedAt)
            removeApplication(player, ArcMenuApplicationCloseReason.BACK, preservePointer = true)
        }
        return when (target) {
            is MenuRoute -> open(player, target.menuId, remember = false, arguments = target.arguments)
            is ApplicationRoute -> openApplication(player, target.applicationId, remember = false, arguments = target.arguments)
        }
    }

    override fun refresh(player: Player, target: String?) {
        val session = sessions[player.uniqueId]?.takeIf { it.mode == MenuViewMode.RUNTIME } ?: return
        if (target == null) {
            session.textNodes.forEach { (id, node) -> session.view.updateText(id, placeholders.expand(player, node.content)) }
            session.imageNodes.forEach { (id, node) -> session.view.updateImage(id, placeholders.expand(player, node.source)) }
            refreshTooltip(session, force = true)
            return
        }
        val node = session.textNodes[target]
        if (node != null) {
            session.view.updateText(target, placeholders.expand(player, node.content))
            return
        }
        val image = session.imageNodes[target]
        if (image != null) {
            session.view.updateImage(target, placeholders.expand(player, image.source))
            return
        }
        if (session.lastRegion?.id == target) {
            refreshTooltip(session, force = true)
            return
        }
        message(player, "runtime.refresh-missing", target.orEmpty())
    }

    override fun schedule(player: Player, delayTicks: Long, task: () -> Unit) {
        val expected = sessions[player.uniqueId] ?: return
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (player.isOnline && sessions[player.uniqueId] === expected) task()
        }, delayTicks.coerceAtLeast(1L))
    }

    override fun startCatcher(player: Player, catcher: CatcherAction) {
        val owner = sessions[player.uniqueId]?.takeIf { it.mode == MenuViewMode.RUNTIME } ?: return
        if (catcher.stages.isEmpty()) return
        state.remove(player, StateScope.META, Regex("input(?:-.*)?"))
        startCatcherStage(player, CatcherInput(owner, catcher, 0))
    }

    override fun retype(player: Player) {
        if (catcherInputs.containsKey(player.uniqueId)) retypeRequests += player.uniqueId
    }

    fun isAwaitingChat(player: Player): Boolean = player.uniqueId in awaitingChats

    fun acceptChat(player: Player, message: String) {
        val input = catcherInputs[player.uniqueId] ?: return
        if (sessions[player.uniqueId] !== input.owner) {
            catcherInputs.remove(player.uniqueId)
            awaitingChats.remove(player.uniqueId)
            return
        }
        val stage = input.catcher.stages[input.stage]
        state.set(player, StateScope.META, "input", message)
        if (stage.id.isNotBlank()) state.set(player, StateScope.META, "input-${stage.id}", message)
        if (message.matches(Regex("(?i)cancel|quit|end|q"))) {
            catcherInputs.remove(player.uniqueId)
            awaitingChats.remove(player.uniqueId)
            executor.run(stage.cancel, player, this)
            return
        }
        val result = executor.run(stage.end, player, this)
        if (result == ActionResult.RETURN) {
            catcherInputs.remove(player.uniqueId)
            awaitingChats.remove(player.uniqueId)
            retypeRequests.remove(player.uniqueId)
            return
        }
        if (retypeRequests.remove(player.uniqueId)) {
            startCatcherStage(player, input)
            return
        }
        val next = input.stage + 1
        if (next >= input.catcher.stages.size) {
            catcherInputs.remove(player.uniqueId)
            awaitingChats.remove(player.uniqueId)
        }
        else startCatcherStage(player, input.copy(stage = next))
    }

    private fun startCatcherStage(player: Player, input: CatcherInput) {
        if (sessions[player.uniqueId] !== input.owner) return
        catcherInputs[player.uniqueId] = input
        awaitingChats += player.uniqueId
        executor.run(input.catcher.stages[input.stage].start, player, this)
        if (sessions[player.uniqueId] !== input.owner) catcherInputs.remove(player.uniqueId)
    }

    fun activate(player: Player, button: PointerButton) {
        val application = applicationSessions[player.uniqueId]
        if (application != null) {
            if (!acceptsActivation(application.pointerMode, button)) return
            if (application.pointerMode == PointerMode.MOUSE && !pointer.isReady(player)) return
            val now = System.nanoTime()
            val previous = lastActivation.put(player.uniqueId, now)
            if (previous != null && now - previous < CLICK_DEBOUNCE_NANOS) return
            val point = pointer.sample(player, application.plane)
            invokeApplication(application, "activate") {
                application.callback?.onActivate(
                    ArcMenuActivateEvent(point.api(), button.api(), application.pointerMode.api(), player.isSneaking),
                )
            }
            return
        }
        val session = sessions[player.uniqueId] ?: return
        if (!acceptsActivation(session.pointerMode, button)) return
        if (session.pointerMode == PointerMode.MOUSE && !pointer.isReady(player)) return
        val now = System.nanoTime()
        val previous = lastActivation.put(player.uniqueId, now)
        if (previous != null && now - previous < CLICK_DEBOUNCE_NANOS) return
        val point = pointer.sample(player, session.plane)
        val region = session.hitTester.hit(point)
        if (session.mode != MenuViewMode.RUNTIME) {
            player.sendMessage(Component.text(language.text(player, "runtime.preview-click", describe(player, point, region?.id))))
            return
        }
        // The backend remains at its configured screen-aligned plane, but business
        // input is suspended while the visual root is away from that base pose.
        if (session.transitionActive || session.closing) return
        if (region == null) return
        // Both pointer modes use right click as their primary transport. Keep the
        // established right/shift-right YAML route in either mode.
        val input = if (player.isSneaking) ClickInput.SHIFT_RIGHT else ClickInput.RIGHT
        val condition = region.condition
        if (condition != null && !executor.evaluate(condition, player)) {
            executor.runActions(region.deny, player, this)
            return
        }
        executor.runOrdered(region.actions.matching(input), player, this)
    }

    /** Returns true when ArcMenu owns the hotbar change and the Bukkit event must be cancelled. */
    fun hotbarScroll(player: Player, previousSlot: Int, newSlot: Int): Boolean {
        val runtime = applicationSessions[player.uniqueId] ?: return false
        if (runtime.pointerMode != PointerMode.MOUSE || !runtime.registration.options.captureMouseScroll) return false
        if (!pointer.isReady(player)) return true
        val steps = hotbarScrollSteps(previousSlot, newSlot)
        if (steps != 0) {
            val point = pointer.sample(player, runtime.plane)
            invokeApplication(runtime, "scroll") {
                runtime.callback?.onScroll(ArcMenuScrollEvent(steps, point.api()))
            }
        }
        return true
    }

    fun isCapture(player: Player, entity: Entity): Boolean {
        val capture = applicationSessions[player.uniqueId]?.capture ?: sessions[player.uniqueId]?.capture ?: return false
        if (capture.uniqueId != entity.uniqueId) return false
        return entity.persistentDataContainer.get(captureKey, PersistentDataType.STRING) == player.uniqueId.toString()
    }

    fun diagnostic(player: Player): String? {
        applicationSessions[player.uniqueId]?.let { runtime ->
            return describe(player, pointer.sample(player, runtime.plane), runtime.registration.id)
        }
        val session = sessions[player.uniqueId] ?: return null
        val point = pointer.sample(player, session.plane)
        return describe(player, point, session.hitTester.hit(point)?.id)
    }

    private fun createSession(
        player: Player,
        menu: MenuDefinition,
        mode: MenuViewMode,
        placement: Placement? = null,
        transitionKind: TransitionKind? = null,
    ): Session {
        // Editor previews deliberately enter the same fixed-camera transport as
        // mouse-mode runtime menus.  The Fabric screen uses the native cursor,
        // but the server camera is what makes the editor face the real menu.
        val intendedPointerMode = if (mode == MenuViewMode.RUNTIME) pointer.mode(player) else PointerMode.MOUSE
        val canvas = menu.canvas.forRuntimeScreen(runtimeScreen.forMode(intendedPointerMode))
        val plane = placement?.plane?.withCanvas(canvas) ?: plane(player, canvas, intendedPointerMode)
        val timeline = if (mode == MenuViewMode.RUNTIME) animations().forMenu(menu.id)?.let {
            AnimationTimeline(it, nodeTransforms(menu.frontend), transitionKind)
        } else null
        val initialAnimation = timeline?.initial() ?: TimelineTick(AnimationSnapshot(), false)
        val cursorStyle = pointer.settings.cursorStyle.takeIf {
            mode == MenuViewMode.RUNTIME && intendedPointerMode == PointerMode.MOUSE
        }
        var view: BukkitMenuRenderer.MenuView? = null
        var capture: Interaction? = null
        return try {
            val createdView = renderer.create(
                player, menu, plane, mode,
                resolve = { placeholders.expand(player, it) },
                tooltipStyle = if (mode == MenuViewMode.RUNTIME) tooltipStyles.forMouse(intendedPointerMode == PointerMode.MOUSE) else null,
                cursorStyle = cursorStyle,
                initialAnimation = initialAnimation.snapshot,
            )
            view = createdView
            val createdCapture = capture(player, plane)
            capture = createdCapture
            // The complete visual scene is sent before the camera takeover. The initial
            // animation snapshot remains frozen until PointerController receives the
            // client's acknowledgement of the ordered activation packets.
            val pointerMode = if (mode == MenuViewMode.RUNTIME) pointer.begin(player, plane) else pointer.beginEditor(player, plane)
            val session = Session(
                player, menu, plane, mode, createdView, HitTester(menu.backend), placement?.worldId ?: player.world.uid,
                placement?.openedAt ?: vec(player.location.toVector()), createdCapture,
                textNodes(menu.frontend), imageNodes(menu.frontend),
                timeline, pointerMode, initialAnimation.snapshot, initialAnimation.transitionActive,
            )
            sessions[player.uniqueId] = session
            ensureTicker()
            session
        } catch (error: Exception) {
            capture?.remove()
            view?.close()
            if (pointer.isMouseActive(player) && sessions[player.uniqueId] == null) pointer.end(player)
            throw error
        }
    }

    private fun createApplication(
        player: Player,
        registration: RegisteredApplication,
        arguments: List<String>,
        designCanvas: Canvas,
        placement: Placement?,
    ): Boolean {
        val pointerMode = pointer.mode(player)
        val canvas = designCanvas.forRuntimeScreen(runtimeScreen.forMode(pointerMode))
        val applicationPlane = placement?.plane?.withCanvas(canvas) ?: plane(player, canvas, pointerMode)
        val cursorStyle = pointer.settings.cursorStyle.takeIf { pointerMode == PointerMode.MOUSE }
        var overlay: BukkitMenuRenderer.MenuView? = null
        var capture: Interaction? = null
        var activeMode = pointerMode
        try {
            overlay = renderer.create(
                player,
                emptyApplicationMenu(canvas),
                applicationPlane,
                MenuViewMode.RUNTIME,
                tooltipStyle = null,
                cursorStyle = cursorStyle,
            )
            capture = capture(player, applicationPlane)
            activeMode = pointer.begin(player, applicationPlane)
            val runtime = HostedApplication(
                registration,
                player,
                arguments.toList(),
                designCanvas,
                applicationPlane,
                placement?.worldId ?: player.world.uid,
                placement?.openedAt ?: vec(player.location.toVector()),
                capture,
                overlay,
                activeMode,
            )
            runtime.context = ApplicationContext(runtime)
            runtime.lastPoint = pointer.sample(player, applicationPlane)
            applicationSessions[player.uniqueId] = runtime
            runtime.lastPoint?.let { point -> cursorStyle?.let { overlay.updateCursor(point, it) } }

            val callback = registration.application.open(runtime.context)
            runtime.callback = requireNotNull(callback) { "应用工厂返回了 null 会话" }
            if (applicationSessions[player.uniqueId] !== runtime) {
                runCatching {
                    callback.onClose(ArcMenuApplicationCloseEvent(ArcMenuApplicationCloseReason.REPLACED))
                }
                return true
            }
            if (!invokeApplication(runtime, "surface") {
                    callback.onSurfaceChanged(ArcMenuSurfaceEvent(runtime.context.surface(), true))
                }) return false
            ensureTicker()
            return true
        } catch (error: Throwable) {
            val current = applicationSessions[player.uniqueId]
            val failedSessionStillOwnsPlayer = current?.registration?.token == registration.token
            val noReplacement = current == null && sessions[player.uniqueId] == null
            val target = if (failedSessionStillOwnsPlayer || noReplacement) pollHistory(player) else null
            if (current != null && current.registration.token == registration.token) {
                removeApplication(player, ArcMenuApplicationCloseReason.ERROR, preservePointer = target != null)
            } else {
                capture?.remove()
                overlay?.close()
                if (activeMode == PointerMode.MOUSE && noReplacement) pointer.end(player)
            }
            reportApplicationFailure(player, registration.id, "open", error)
            if (target != null && player.isOnline) {
                openRoute(
                    player,
                    target,
                    placement ?: Placement(applicationPlane, player.world.uid, vec(player.location.toVector())),
                )
            }
            return false
        }
    }

    private fun emptyApplicationMenu(canvas: Canvas) = MenuDefinition(
        id = "__hosted_application__",
        canvas = canvas,
        frontend = emptyList(),
        backend = emptyList(),
    )

    private fun tick() {
        val started = System.nanoTime()
        try {
            tickSessions()
            tickApplications()
        } finally {
            performance.recordTick(System.nanoTime() - started)
        }
    }

    private fun tickSessions() {
        for (session in sessions.values.toList()) {
            val player = session.player
            if (!player.isOnline) {
                closeImmediately(player, true)
                continue
            }
            session.ticks++
            if (session.mode == MenuViewMode.RUNTIME) {
                if (player.isDead) continue
                val cameraInvalid = session.pointerMode == PointerMode.MOUSE && !pointer.hasValidCamera(player)
                if (player.world.uid != session.worldId || !session.view.valid || !session.capture.isValid || cameraInvalid) {
                    if (session.ticks % REPAIR_INTERVAL_TICKS == 0L) reanchorRuntime(player)
                    continue
                }
            } else {
                val delta = vec(player.location.toVector()) - session.openedAt
                val cameraInvalid = session.pointerMode == PointerMode.MOUSE && !pointer.hasValidCamera(player)
                if (!player.hasPermission("arcmenu.admin") || player.world.uid != session.worldId ||
                    delta.dot(delta) > 25.0 || !session.view.valid || !session.capture.isValid || cameraInvalid) {
                    closeImmediately(player, true)
                    continue
                }
            }
            val cameraReady = session.pointerMode != PointerMode.MOUSE || pointer.isReady(player)
            if (!cameraReady) {
                session.view.hideTooltip()
                continue
            }
            val timelineTick = session.timeline?.advance()
            if (timelineTick != null) {
                if (timelineTick.exitFinished) {
                    removeSession(player, runCloseEvent = false)
                    continue
                }
                applyAnimation(session, timelineTick)
            }
            // Runtime mouse mode samples hidden yaw/pitch.  In the editor the
            // native cursor belongs to EditorScreen, so polling rotation would
            // create a second, unrelated cursor coordinate stream.
            if (session.mode == MenuViewMode.RUNTIME && session.pointerMode == PointerMode.MOUSE) pointer.poll(player)
            val point = if (session.mode == MenuViewMode.RUNTIME) pointer.sample(player, session.plane) else null
            val region = session.hitTester.hit(point)
            val pointerChanged = !Transforms.approximatelySame(point, session.lastPoint)
            val regionChanged = region?.id != session.lastRegion?.id
            val cursorChoosing = session.mode == MenuViewMode.RUNTIME && !session.transitionActive && !session.closing && region != null
            if (point != null && session.pointerMode == PointerMode.MOUSE &&
                (pointerChanged || cursorChoosing != session.cursorChoosing)) {
                session.view.updateCursor(point, pointer.settings.cursorStyle, cursorChoosing)
            }
            session.cursorChoosing = cursorChoosing
            when (session.mode) {
                MenuViewMode.BACKEND_PREVIEW -> {
                    session.view.select(region?.id)
                    if (!Transforms.approximatelySame(point, session.lastPoint) || region?.id != session.lastRegion?.id) {
                        player.sendActionBar(Component.text(describe(player, point, region?.id)))
                    }
                }
                MenuViewMode.RUNTIME -> {
                    if (session.transitionActive || session.closing) {
                        session.view.hideTooltip()
                        session.lastPoint = point
                        session.lastRegion = region
                        continue
                    }
                    for ((id, node) in session.textNodes) {
                        if (node.updateTicks > 0 && session.ticks % node.updateTicks == 0L &&
                            session.timeline?.owns(id, TrackProperty.CONTENT) != true) {
                            session.view.updateText(id, placeholders.expand(player, node.content))
                        }
                    }
                    for ((id, node) in session.imageNodes) {
                        if (node.updateTicks > 0 && session.ticks % node.updateTicks == 0L) {
                            session.view.updateImage(id, placeholders.expand(player, node.source))
                        }
                    }
                    val intervalDue = region?.tooltipUpdateTicks?.let { it > 0 && session.ticks % it == 0L } == true
                    if (pointerChanged || regionChanged || intervalDue) refreshTooltip(session, point, region, force = intervalDue)
                }
                MenuViewMode.FRONTEND_PREVIEW -> Unit
            }
            session.lastPoint = point
            session.lastRegion = region
        }
    }

    private fun tickApplications() {
        for (runtime in applicationSessions.values.toList()) {
            if (applicationSessions[runtime.player.uniqueId] !== runtime) continue
            val player = runtime.player
            if (!player.isOnline) {
                removeApplication(player, ArcMenuApplicationCloseReason.PLAYER_QUIT)
                continue
            }
            if (!runtime.registration.owner.isEnabled) {
                closeApplications(runtime.registration.owner)
                continue
            }
            runtime.ticks++
            if (player.isDead) continue
            val cameraInvalid = runtime.pointerMode == PointerMode.MOUSE && !pointer.hasValidCamera(player)
            if (player.world.uid != runtime.worldId || !runtime.overlay.valid || !runtime.capture.isValid || cameraInvalid) {
                if (runtime.ticks % REPAIR_INTERVAL_TICKS == 0L) reanchorApplication(runtime)
                continue
            }
            if (runtime.pointerMode == PointerMode.MOUSE && !pointer.isReady(player)) continue
            if (runtime.pointerMode == PointerMode.MOUSE) pointer.poll(player)
            val point = pointer.sample(player, runtime.plane)
            val previous = runtime.lastPoint
            if (!Transforms.approximatelySame(point, previous)) {
                if (point != null && runtime.pointerMode == PointerMode.MOUSE) {
                    runtime.overlay.updateCursor(point, pointer.settings.cursorStyle, runtime.cursorInteractive)
                }
                runtime.lastPoint = point
                if (!invokeApplication(runtime, "pointer") {
                        runtime.callback?.onPointerMove(ArcMenuPointerMoveEvent(point.api(), previous.api()))
                    }) continue
            }
            if (applicationSessions[player.uniqueId] !== runtime) continue
            if (runtime.ticks % runtime.registration.options.tickInterval == 0L) {
                invokeApplication(runtime, "tick") {
                    runtime.callback?.onTick(ArcMenuTickEvent(runtime.ticks))
                }
            }
        }
    }

    private fun refreshTooltip(session: Session, force: Boolean) =
        refreshTooltip(session, session.lastPoint, session.lastRegion, force)

    private fun refreshTooltip(session: Session, point: MenuPoint?, region: InteractionRegion?, force: Boolean) {
        if (point == null || region == null || region.tooltip.isEmpty()) {
            session.view.hideTooltip()
            return
        }
        val lines = region.tooltip.map { placeholders.expand(session.player, it) }
        if (force || lines.isNotEmpty()) session.view.updateTooltip(point, lines)
    }

    private fun removeSession(player: Player, runCloseEvent: Boolean, preservePointer: Boolean = false): ActionResult {
        val removed = sessions.remove(player.uniqueId) ?: return ActionResult.CONTINUE
        cancelInput(player)
        if (removed.pointerMode == PointerMode.MOUSE && !preservePointer) pointer.end(player)
        removed.capture.remove()
        removed.view.close()
        if (player.isOnline) player.sendActionBar(Component.empty())
        val result = if (runCloseEvent && !removed.closeEventRun) {
            removed.closeEventRun = true
            executor.run(removed.menu.events.close, player, this)
        } else ActionResult.CONTINUE
        if (sessions.isEmpty() && applicationSessions.isEmpty()) {
            ticker?.cancel()
            ticker = null
        }
        return result
    }

    private fun removeApplication(
        player: Player,
        reason: ArcMenuApplicationCloseReason,
        preservePointer: Boolean = false,
    ) {
        val removed = applicationSessions.remove(player.uniqueId) ?: return
        removed.active = false
        if (removed.pointerMode == PointerMode.MOUSE && !preservePointer) {
            runCatching { pointer.end(player) }.onFailure {
                plugin.logger.warning(language.log("log.app-pointer-close-failed", removed.registration.id, it.message.orEmpty()))
            }
        }
        removed.capture.remove()
        removed.overlay.close()
        runCatching {
            removed.callback?.onClose(ArcMenuApplicationCloseEvent(reason))
        }.onFailure {
            plugin.logger.warning(language.log("log.app-close-callback-failed", removed.registration.id, it.message.orEmpty()))
        }
        removed.cleanupResources()
        lastActivation.remove(player.uniqueId)
        if (player.isOnline) player.sendActionBar(Component.empty())
        if (sessions.isEmpty() && applicationSessions.isEmpty()) {
            ticker?.cancel()
            ticker = null
        }
    }

    fun closeApplications(owner: Plugin) {
        history.values.forEach { stack ->
            stack.removeIf { route ->
                route is ApplicationRoute && applicationRegistry.get(route.applicationId)?.owner === owner
            }
        }
        history.entries.removeIf { it.value.isEmpty() }
        applicationSessions.values.filter { it.registration.owner === owner }.toList().forEach { runtime ->
            val placement = Placement(runtime.plane, runtime.worldId, runtime.openedAt)
            val target = pollHistory(runtime.player)
            removeApplication(
                runtime.player,
                ArcMenuApplicationCloseReason.OWNER_DISABLED,
                preservePointer = target != null,
            )
            if (target != null && runtime.player.isOnline) openRoute(runtime.player, target, placement)
        }
    }

    fun closeApplicationRegistration(token: UUID) {
        history.values.forEach { stack ->
            stack.removeIf { route ->
                route is ApplicationRoute && applicationRegistry.get(route.applicationId)?.token == token
            }
        }
        history.entries.removeIf { it.value.isEmpty() }
        applicationSessions.values.filter { it.registration.token == token }.toList().forEach { runtime ->
            val placement = Placement(runtime.plane, runtime.worldId, runtime.openedAt)
            val target = pollHistory(runtime.player)
            removeApplication(
                runtime.player,
                ArcMenuApplicationCloseReason.OWNER_DISABLED,
                preservePointer = target != null,
            )
            if (target != null && runtime.player.isOnline) openRoute(runtime.player, target, placement)
        }
    }

    private fun invokeApplication(runtime: HostedApplication, phase: String, callback: () -> Unit): Boolean {
        if (!runtime.active || applicationSessions[runtime.player.uniqueId] !== runtime) return false
        return try {
            callback()
            runtime.active && applicationSessions[runtime.player.uniqueId] === runtime
        } catch (error: Throwable) {
            val placement = Placement(runtime.plane, runtime.worldId, runtime.openedAt)
            val target = pollHistory(runtime.player)
            reportApplicationFailure(runtime.player, runtime.registration.id, phase, error)
            removeApplication(
                runtime.player,
                ArcMenuApplicationCloseReason.ERROR,
                preservePointer = target != null,
            )
            if (target != null && runtime.player.isOnline) openRoute(runtime.player, target, placement)
            false
        }
    }

    private fun reportApplicationFailure(player: Player, applicationId: String, phase: String, error: Throwable) {
        plugin.logger.warning(language.log("log.app-phase-failed", applicationId, phase,
            error.message ?: error.javaClass.simpleName))
        if (player.isOnline) message(player, "runtime.app-failed", applicationId)
    }

    private fun pollHistory(player: Player): Route? {
        val stack = history[player.uniqueId] ?: return null
        val target = stack.poll()
        if (stack.isEmpty()) history.remove(player.uniqueId)
        return target
    }

    private fun openRoute(player: Player, target: Route, placement: Placement): Boolean {
        routePlacement[player.uniqueId] = placement
        return when (target) {
            is MenuRoute -> open(player, target.menuId, remember = false, arguments = target.arguments)
            is ApplicationRoute -> openApplication(player, target.applicationId, remember = false, arguments = target.arguments)
        }
    }

    private fun beginClose(session: Session) {
        if (session.closing) return
        if (!session.closeEventRun) {
            session.closeEventRun = true
            executor.run(session.menu.events.close, session.player, this)
            if (sessions[session.player.uniqueId] !== session) return
        }
        cancelInput(session.player)
        session.view.hideTooltip()
        val animated = session.timeline?.beginExit() == true
        if (!animated) {
            removeSession(session.player, runCloseEvent = false)
            return
        }
        session.closing = true
        applyAnimation(session, session.timeline.initial())
    }

    private fun cancelInput(player: Player) {
        catcherInputs.remove(player.uniqueId)
        awaitingChats.remove(player.uniqueId)
        retypeRequests.remove(player.uniqueId)
    }

    private fun applyAnimation(session: Session, tick: TimelineTick) {
        session.transitionActive = tick.transitionActive
        if (tick.snapshot == session.lastAnimation) return
        session.view.updateAnimation(session.menu, tick.snapshot) { placeholders.expand(session.player, it) }
        session.lastAnimation = tick.snapshot
    }

    override fun close() {
        applicationSessions.values.toList().forEach { runtime ->
            removeApplication(runtime.player, ArcMenuApplicationCloseReason.PLUGIN_DISABLE)
        }
        sessions.values.toList().forEach { session ->
            sessions.remove(session.player.uniqueId)
            if (session.pointerMode == PointerMode.MOUSE) pointer.end(session.player)
            session.capture.remove()
            session.view.close()
        }
        sessions.clear()
        applicationSessions.clear()
        history.clear()
        lastActivation.clear()
        routeDepth.clear()
        routePlacement.clear()
        catcherInputs.clear()
        awaitingChats.clear()
        retypeRequests.clear()
        ticker?.cancel()
        ticker = null
    }

    private fun capture(player: Player, plane: MenuPlane): Interaction {
        val worldWidth = max(plane.canvas.width / plane.canvas.pixelsPerBlock, 0.1)
        val worldHeight = max(plane.canvas.height / plane.canvas.pixelsPerBlock, 0.1)
        val bottom = plane.origin - plane.up * (worldHeight / 2.0)
        val entity = player.world.spawn(Location(player.world, bottom.x, bottom.y, bottom.z), Interaction::class.java) {
            it.interactionWidth = worldWidth.toFloat()
            it.interactionHeight = worldHeight.toFloat()
            it.isResponsive = true
            it.isInvisible = true
            it.isPersistent = false
            it.setGravity(false)
            it.isInvulnerable = true
            it.isSilent = true
            it.isVisibleByDefault = false
            it.persistentDataContainer.set(captureKey, PersistentDataType.STRING, player.uniqueId.toString())
        }
        player.showEntity(plugin, entity)
        return entity
    }

    private fun plane(player: Player, canvas: Canvas, pointerMode: PointerMode = PointerMode.TOUCH): MenuPlane {
        val eye = player.eyeLocation
        // The camera entity's yaw reaches the client as a byte. Mouse mode must
        // build its plane from that same decoded angle or the near-eye screen
        // becomes a subtly trapezoidal projection.
        val yaw = if (pointerMode == PointerMode.MOUSE) networkCameraAngle(eye.yaw) else eye.yaw
        val forward = Vector(-kotlin.math.sin(Math.toRadians(yaw.toDouble())), 0.0, kotlin.math.cos(Math.toRadians(yaw.toDouble())))
        val normal = forward.clone().multiply(-1)
        val up = Vector(0, 1, 0)
        val right = up.clone().crossProduct(normal).normalize()
        val offset = runtimeScreen.forMode(pointerMode).offset
        val origin = eye.toVector()
            .add(forward.clone().multiply(canvas.distance))
            .add(right.clone().multiply(offset.x))
            .add(up.clone().multiply(offset.y))
            .add(normal.clone().multiply(offset.z))
        return MenuPlane(
            vec(origin),
            vec(right), vec(up), vec(normal), canvas,
        )
    }

    private fun textNodes(nodes: List<VisualNode>): Map<String, TextNode> = buildMap {
        fun visit(node: VisualNode) {
            when (node) {
                is TextNode -> put(node.properties.id, node)
                is GroupNode -> node.children.forEach(::visit)
                else -> Unit
            }
        }
        nodes.forEach(::visit)
    }

    private fun imageNodes(nodes: List<VisualNode>): Map<String, ImageNode> = buildMap {
        fun visit(node: VisualNode) {
            when (node) {
                is ImageNode -> put(node.properties.id, node)
                is GroupNode -> node.children.forEach(::visit)
                else -> Unit
            }
        }
        nodes.forEach(::visit)
    }

    private fun nodeTransforms(nodes: List<VisualNode>): Map<String, Transform> = buildMap {
        fun visit(node: VisualNode) {
            put(node.properties.id, node.properties.transform)
            if (node is GroupNode) node.children.forEach(::visit)
        }
        nodes.forEach(::visit)
    }

    private fun ensureTicker() {
        if (ticker == null) ticker = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 1L, 1L)
    }

    private fun describe(player: Player, point: MenuPoint?, id: String?): String = if (point == null) {
        language.text(player, "runtime.point-none")
    } else language.text(player, "runtime.point", decimal(point.x), decimal(point.y),
        id ?: language.text(player, "common.none"))

    private fun message(player: Player, key: String, vararg arguments: Any?) =
        player.sendMessage(Component.text("[ArcMenu] ${language.text(player, key, *arguments)}"))

    private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    private fun vec(vector: Vector) = Vec3(vector.x, vector.y, vector.z)

    private fun apiSurface(worldId: UUID, plane: MenuPlane): ArcMenuSurface {
        val world = requireNotNull(Bukkit.getWorld(worldId)) { "应用屏幕所在世界已卸载" }
        return ArcMenuSurface(
            world,
            Location(world, plane.origin.x, plane.origin.y, plane.origin.z),
            Vector(plane.right.x, plane.right.y, plane.right.z),
            Vector(plane.up.x, plane.up.y, plane.up.z),
            Vector(plane.normal.x, plane.normal.y, plane.normal.z),
            ArcMenuCanvas(
                plane.canvas.width,
                plane.canvas.height,
                plane.canvas.pixelsPerBlock,
                plane.canvas.distance,
            ),
        )
    }

    private companion object {
        const val CLICK_DEBOUNCE_NANOS = 75_000_000L
        const val MAX_ROUTE_DEPTH = 8
        const val REPAIR_INTERVAL_TICKS = 20L
    }
}

internal fun hotbarScrollSteps(previousSlot: Int, newSlot: Int): Int = when {
    previousSlot !in 0..8 || newSlot !in 0..8 || previousSlot == newSlot -> 0
    previousSlot == 8 && newSlot == 0 -> 1
    previousSlot == 0 && newSlot == 8 -> -1
    newSlot - previousSlot == 1 -> 1
    newSlot - previousSlot == -1 -> -1
    else -> 0
}

private fun ArcMenuCanvas.core() = Canvas(width, height, pixelsPerBlock, designDistance)
private fun MenuPoint?.api(): ArcMenuPoint? = this?.let { ArcMenuPoint(it.x, it.y) }
private fun PointerMode.api(): ArcMenuPointerMode = ArcMenuPointerMode.valueOf(name)
private fun PointerButton.api(): ArcMenuPointerButton = ArcMenuPointerButton.valueOf(name)
