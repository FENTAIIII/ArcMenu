package com.fentai.arcmenu.api

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import java.util.function.Consumer

/** A third-party application factory. One session instance is created for each open player. */
fun interface ArcMenuApplication {
    fun open(context: ArcMenuApplicationContext): ArcMenuApplicationSession
}

/**
 * Per-player application callbacks. Extend this class from Java and override only the events used by the app.
 * Every callback is invoked on the Bukkit server thread.
 */
abstract class ArcMenuApplicationSession {
    open fun onSurfaceChanged(event: ArcMenuSurfaceEvent) = Unit
    open fun onPointerModeChanged(event: ArcMenuPointerModeEvent) = Unit
    open fun onPointerMove(event: ArcMenuPointerMoveEvent) = Unit
    open fun onActivate(event: ArcMenuActivateEvent) = Unit
    open fun onScroll(event: ArcMenuScrollEvent) = Unit
    open fun onTick(event: ArcMenuTickEvent) = Unit
    open fun onClose(event: ArcMenuApplicationCloseEvent) = Unit
}

/** Stable navigation and resource boundary owned by one application session. */
interface ArcMenuApplicationContext {
    val player: Player
    val applicationId: String
    val arguments: List<String>
    val owner: Plugin
    fun surface(): ArcMenuSurface
    fun pointer(): ArcMenuPoint?
    fun pointerMode(): ArcMenuPointerMode
    fun isActive(): Boolean

    /** Changes only the ArcMenu cursor glyph; applications still decide their own hit regions. */
    fun setCursorInteractive(interactive: Boolean)

    /** Tracked objects are forcibly cleaned when this session closes. */
    fun <T : Entity> spawnPrivate(location: Location, type: Class<T>, initializer: Consumer<T>): T
    fun <T : Entity> track(entity: T): T
    fun <T : BukkitTask> track(task: T): T
    fun <T : AutoCloseable> track(resource: T): T

    /** Runs only if this exact application session is still active. */
    fun execute(task: Runnable): Boolean
    fun runLater(delayTicks: Long, task: Runnable): BukkitTask
    fun runTimer(delayTicks: Long, periodTicks: Long, task: Runnable): BukkitTask

    fun openMenu(menuId: String, arguments: List<String>): Boolean
    fun openApplication(applicationId: String, arguments: List<String>): Boolean
    fun back(): Boolean
    fun close()
}

class ArcMenuApplicationOptions private constructor(
    val permission: String,
    val captureMouseScroll: Boolean,
    val inheritCanvas: Boolean,
    val canvas: ArcMenuCanvas,
    val tickInterval: Int,
) {
    class Builder internal constructor() {
        private var permission = ""
        private var captureMouseScroll = false
        private var inheritCanvas = true
        private var canvas = ArcMenuCanvas(320.0, 180.0, 100.0, 3.0)
        private var tickInterval = 1

        fun permission(value: String) = apply { permission = value.trim() }
        fun captureMouseScroll(value: Boolean) = apply { captureMouseScroll = value }
        fun inheritCanvas(value: Boolean) = apply { inheritCanvas = value }
        fun canvas(value: ArcMenuCanvas) = apply { canvas = value }
        fun tickInterval(value: Int) = apply { tickInterval = value }
        fun build() = ArcMenuApplicationOptions(permission, captureMouseScroll, inheritCanvas, canvas, tickInterval)
    }

    companion object {
        @JvmStatic fun builder(): Builder = Builder()
        @JvmField val DEFAULT: ArcMenuApplicationOptions = builder().build()
    }
}

data class ArcMenuCanvas(
    val width: Double,
    val height: Double,
    val pixelsPerBlock: Double,
    val designDistance: Double,
)

/** Immutable snapshot of ArcMenu's authoritative screen plane. */
class ArcMenuSurface(
    val world: World,
    origin: Location,
    right: Vector,
    up: Vector,
    normal: Vector,
    val canvas: ArcMenuCanvas,
) {
    private val originValue = origin.clone()
    private val rightValue = right.clone()
    private val upValue = up.clone()
    private val normalValue = normal.clone()

    fun origin(): Location = originValue.clone()
    fun right(): Vector = rightValue.clone()
    fun up(): Vector = upValue.clone()
    fun normal(): Vector = normalValue.clone()

    /** X/Y are logical canvas units; positive depth moves toward the viewer. */
    @JvmOverloads
    fun toWorld(x: Double, y: Double, depth: Double = 0.0): Location = originValue.clone().add(
        rightValue.clone().multiply(x / canvas.pixelsPerBlock)
            .add(upValue.clone().multiply(y / canvas.pixelsPerBlock))
            .add(normalValue.clone().multiply(depth / canvas.pixelsPerBlock)),
    )
}

data class ArcMenuPoint(val x: Double, val y: Double)
enum class ArcMenuPointerButton { LEFT, RIGHT }
enum class ArcMenuApplicationCloseReason { REQUESTED, BACK, REPLACED, PLAYER_QUIT, PLUGIN_DISABLE, OWNER_DISABLED, ERROR }

data class ArcMenuSurfaceEvent(val surface: ArcMenuSurface, val initial: Boolean)
data class ArcMenuPointerModeEvent(val previous: ArcMenuPointerMode, val current: ArcMenuPointerMode)
data class ArcMenuPointerMoveEvent(val point: ArcMenuPoint?, val previous: ArcMenuPoint?)
data class ArcMenuActivateEvent(
    val point: ArcMenuPoint?,
    val button: ArcMenuPointerButton,
    val pointerMode: ArcMenuPointerMode,
    val sneaking: Boolean,
)
/** Positive steps select the next hotbar slot; negative steps select the previous one. */
data class ArcMenuScrollEvent(val steps: Int, val point: ArcMenuPoint?)
data class ArcMenuTickEvent(val tick: Long)
data class ArcMenuApplicationCloseEvent(val reason: ArcMenuApplicationCloseReason)

interface ArcMenuApplicationHandle : AutoCloseable {
    val applicationId: String
    val owner: Plugin
    fun isRegistered(): Boolean
    fun unregister()
    override fun close() = unregister()
}
