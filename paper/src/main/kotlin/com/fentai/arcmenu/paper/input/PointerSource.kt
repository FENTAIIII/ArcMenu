package com.fentai.arcmenu.paper.input

import com.fentai.arcmenu.core.geometry.MenuPlane
import com.fentai.arcmenu.core.geometry.Ray
import com.fentai.arcmenu.core.model.Canvas
import com.fentai.arcmenu.core.model.MenuPoint
import com.fentai.arcmenu.core.model.Vec3
import com.fentai.arcmenu.paper.render.CursorStyle
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.atan2

/** Runtime consumes menu coordinates without knowing which pointer implementation produced them. */
fun interface PointerSource {
    fun sample(player: Player, plane: MenuPlane): MenuPoint?
}

enum class PointerMode { TOUCH, MOUSE }
enum class PointerPolicy { PLAYER_CHOICE, FORCE_TOUCH, FORCE_MOUSE }

data class PointerSettings(
    val policy: PointerPolicy,
    val defaultMode: PointerMode,
    val sensitivityX: Double,
    val sensitivityY: Double,
    val clampMargin: Double,
    val cursorStyle: CursorStyle,
)

data class PointerPreferenceResult(val accepted: Boolean, val mode: PointerMode)

object PointerSettingsLoader {
    fun load(plugin: Plugin): PointerSettings = from(plugin.config)

    internal fun from(config: org.bukkit.configuration.file.FileConfiguration): PointerSettings {
        config.getConfigurationSection("mouse")?.getKeys(false)?.firstOrNull { it !in setOf("policy", "default", "cursor") }?.let {
            throw IllegalArgumentException("config.yml mouse.$it 是不支持的字段")
        }
        // Keep one-reload compatibility with the rejected prototype config. These
        // values are ignored: the cursor image and opacity are fixed by ArcMenu.
        config.getConfigurationSection("mouse.cursor")?.getKeys(false)?.firstOrNull {
            it !in setOf("sensitivity-x", "sensitivity-y", "clamp-margin", "size", "z", "glyph", "color", "opacity")
        }?.let { throw IllegalArgumentException("config.yml mouse.cursor.$it 是不支持的字段") }
        fun string(path: String, default: String): String = config.get(path)?.let { value ->
            value as? String ?: throw IllegalArgumentException("config.yml $path 必须为字符串")
        } ?: default
        fun number(path: String, default: Double): Double = config.get(path)?.let { value ->
            (value as? Number)?.toDouble() ?: throw IllegalArgumentException("config.yml $path 必须为数字")
        } ?: default

        val policy = when (string("mouse.policy", "player-choice").lowercase()) {
            "player-choice" -> PointerPolicy.PLAYER_CHOICE
            "force-crosshair", "force-touch" -> PointerPolicy.FORCE_TOUCH
            "force-cursor", "force-mouse" -> PointerPolicy.FORCE_MOUSE
            else -> throw IllegalArgumentException("config.yml mouse.policy 只能为 player-choice、force-touch 或 force-mouse")
        }
        val defaultMode = when (string("mouse.default", "touch").lowercase()) {
            "crosshair", "touch" -> PointerMode.TOUCH
            "cursor", "mouse" -> PointerMode.MOUSE
            else -> throw IllegalArgumentException("config.yml mouse.default 只能为 touch 或 mouse")
        }
        val sensitivityX = number("mouse.cursor.sensitivity-x", 2.0)
        val sensitivityY = number("mouse.cursor.sensitivity-y", 2.0)
        val margin = number("mouse.cursor.clamp-margin", 3.0)
        val size = number("mouse.cursor.size", 12.0)
        val depth = number("mouse.cursor.z", 5.0)
        require(sensitivityX.isFinite() && sensitivityX in 0.05..50.0) { "config.yml mouse.cursor.sensitivity-x 必须介于 0.05 和 50" }
        require(sensitivityY.isFinite() && sensitivityY in 0.05..50.0) { "config.yml mouse.cursor.sensitivity-y 必须介于 0.05 和 50" }
        require(margin.isFinite() && margin >= 0.0) { "config.yml mouse.cursor.clamp-margin 必须为有限非负数" }
        require(size.isFinite() && size > 0.0) { "config.yml mouse.cursor.size 必须为有限正数" }
        require(depth.isFinite()) { "config.yml mouse.cursor.z 必须为有限数字" }
        return PointerSettings(policy, defaultMode, sensitivityX, sensitivityY, margin, CursorStyle(size, depth))
    }
}

/**
 * Packet-driven cursor with a private fixed camera. LOOK/POSITION_LOOK/POSITION
 * packets are consumed while active, so hidden rotation and movement never reach
 * the server player. Bukkit entity work remains on the server thread.
 */
class PointerController(
    private val plugin: Plugin,
    initialSettings: PointerSettings,
    private val entitySpawned: () -> Unit = {},
) : PointerSource, AutoCloseable {
    private data class CursorState(
        var point: MenuPoint,
        var canvas: Canvas,
        var lastYaw: Float,
        var lastPitch: Float,
        var restoreYaw: Float,
        var restorePitch: Float,
        var camera: TextDisplay,
        var recentring: Boolean = false,
        val activation: CameraActivationGate = CameraActivationGate(),
        val exit: CameraActivationGate = CameraActivationGate(),
        var ending: Boolean = false,
    )

    private val touch = TouchPointer()
    private val cursors = ConcurrentHashMap<UUID, CursorState>()
    private val preferenceKey = NamespacedKey(plugin, "pointer-mode")
    private val packets = ProtocolPointerBridge(plugin, ::consumeRotation, ::consumePosition, ::acceptSyntheticTeleport)
    @Volatile var settings: PointerSettings = initialSettings
        private set

    fun updateSettings(next: PointerSettings) {
        settings = next
    }

    fun begin(player: Player, plane: MenuPlane): PointerMode {
        return begin(player, plane, mode(player))
    }

    /**
     * The editor always uses the fixed-camera mouse-mode transport.  This is
     * intentionally independent from the player's runtime pointer preference:
     * the Fabric screen owns the visible OS cursor, while this controller owns
     * the camera that keeps the real menu squarely behind the editor viewport.
     */
    fun beginEditor(player: Player, plane: MenuPlane): PointerMode {
        return begin(player, plane, PointerMode.MOUSE)
    }

    private fun begin(player: Player, plane: MenuPlane, mode: PointerMode): PointerMode {
        check(Bukkit.isPrimaryThread()) { "摄像机实体只能在服务端主线程创建" }
        if (mode != PointerMode.MOUSE) {
            end(player)
            return mode
        }
        val existing = cursors[player.uniqueId]
        if (existing == null) {
            val camera = spawnCamera(player, plane)
            val state = CursorState(
                MenuPoint(0.0, 0.0), plane.canvas,
                player.location.yaw, 0.0f,
                player.location.yaw, 0.0f,
                camera,
            )
            cursors[player.uniqueId] = state
            try {
                player.showEntity(plugin, camera)
                // The camera target must be active before the client-only spectator mode is
                // applied. Otherwise the client can render one spectator frame from the
                // player's body and expose underground geometry.
                packets.setCamera(player, camera)
                packets.hideFirstPersonHands(player)
                packets.resetClientRotation(player, state.restoreYaw, state.restorePitch) { id ->
                    state.activation.expect(id)
                }
            } catch (error: Throwable) {
                cursors.remove(player.uniqueId, state)
                camera.remove()
                throw error
            }
        } else {
            val resumeEnding = synchronized(existing) {
                existing.canvas = plane.canvas
                existing.point = clamp(existing.point, plane.canvas)
                val ending = existing.ending
                existing.ending = false
                ending
            }
            if (resumeEnding) {
                // A new menu opened before the acknowledged exit finished. The old exit
                // packets precede these packets on the same connection, so reasserting the
                // existing camera creates one fresh, ordered activation transaction.
                packets.setCamera(player, existing.camera)
                packets.hideFirstPersonHands(player)
                packets.resetClientRotation(player, existing.restoreYaw, existing.restorePitch) { id ->
                    existing.activation.expect(id)
                }
            } else if (!existing.camera.isValid || existing.camera.world.uid != player.world.uid) {
                reanchor(player, plane)
            }
        }
        return mode
    }

    /** Rebuilds only camera state and preserves the cursor point across respawn/world relocation. */
    fun reanchor(player: Player, plane: MenuPlane) {
        check(Bukkit.isPrimaryThread()) { "摄像机实体只能在服务端主线程重建" }
        val state = cursors[player.uniqueId] ?: return
        val replacement = spawnCamera(player, plane)
        player.showEntity(plugin, replacement)
        val centreYaw = player.location.yaw
        try {
            packets.setCamera(player, replacement)
            packets.hideFirstPersonHands(player)
            packets.resetClientRotation(player, centreYaw, 0.0f)
        } catch (error: Throwable) {
            replacement.remove()
            throw error
        }
        val previous = synchronized(state) {
            val old = state.camera
            state.camera = replacement
            state.canvas = plane.canvas
            state.point = clamp(state.point, plane.canvas)
            state.lastYaw = centreYaw
            state.lastPitch = 0.0f
            state.restoreYaw = centreYaw
            state.restorePitch = 0.0f
            state.recentring = false
            old
        }
        previous.remove()
    }

    fun end(player: Player) {
        check(Bukkit.isPrimaryThread()) { "摄像机实体只能在服务端主线程移除" }
        val state = cursors[player.uniqueId] ?: return
        val alreadyEnding = synchronized(state) {
            val previous = state.ending
            state.ending = true
            previous
        }
        if (alreadyEnding) return
        if (!player.isOnline) {
            discard(player)
            return
        }
        try {
            // The client may have predicted its hidden local player into a different position
            // while the server consumed movement packets. Correct it before restoring the mode.
            packets.synchronizeClientPlayer(player)
            packets.restoreClientGameMode(player)
            // This second absolute sync is an ordered barrier. Its acknowledgement proves that
            // both the authoritative position and real game mode were processed before handoff.
            packets.synchronizeClientPlayer(player) { id -> state.exit.expect(id) }
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                if (cursors[player.uniqueId] === state && synchronized(state) { state.ending }) {
                    completeEnd(player, state)
                }
            }, EXIT_ACK_TIMEOUT_TICKS)
        } catch (error: Throwable) {
            completeEndImmediately(player, state)
            throw error
        }
    }

    /** Removes protocol state without sending packets; used once a player is disconnecting. */
    fun discard(player: Player) {
        val state = cursors.remove(player.uniqueId) ?: return
        state.camera.remove()
    }

    private fun completeEnd(player: Player, expected: CursorState) {
        check(Bukkit.isPrimaryThread()) { "摄像机只能在服务端主线程交还" }
        if (!cursors.remove(player.uniqueId, expected)) return
        try {
            if (player.isOnline) packets.setCamera(player, player)
        } catch (error: Throwable) {
            plugin.logger.warning("无法在鼠标模式退出时交还 ${player.name} 的摄像机: ${error.message.orEmpty()}")
        } finally {
            expected.camera.remove()
        }
    }

    private fun completeEndImmediately(player: Player, expected: CursorState) {
        if (!cursors.remove(player.uniqueId, expected)) return
        try {
            if (player.isOnline) {
                runCatching { packets.synchronizeClientPlayer(player) }
                runCatching { packets.restoreClientGameMode(player) }
                runCatching { packets.setCamera(player, player) }
            }
        } finally {
            expected.camera.remove()
        }
    }

    fun hasValidCamera(player: Player): Boolean {
        val state = cursors[player.uniqueId] ?: return false
        return synchronized(state) { state.camera.isValid && state.camera.world.uid == player.world.uid }
    }

    /** True after the client acknowledges the ordered camera activation packet sequence. */
    fun isReady(player: Player): Boolean {
        val state = cursors[player.uniqueId] ?: return true
        return state.activation.isReady()
    }

    fun isMouseActive(player: Player): Boolean = cursors.containsKey(player.uniqueId)

    fun mode(player: Player): PointerMode = when (settings.policy) {
        PointerPolicy.FORCE_TOUCH -> PointerMode.TOUCH
        PointerPolicy.FORCE_MOUSE -> PointerMode.MOUSE
        PointerPolicy.PLAYER_CHOICE -> player.persistentDataContainer
            .get(preferenceKey, PersistentDataType.STRING)
            ?.let(::storedPointerMode)
            ?: settings.defaultMode
    }

    fun setPreference(player: Player, requested: PointerMode): PointerPreferenceResult {
        val forced = when (settings.policy) {
            PointerPolicy.FORCE_TOUCH -> PointerMode.TOUCH
            PointerPolicy.FORCE_MOUSE -> PointerMode.MOUSE
            PointerPolicy.PLAYER_CHOICE -> null
        }
        if (forced != null) return PointerPreferenceResult(requested == forced, forced)
        player.persistentDataContainer.set(preferenceKey, PersistentDataType.STRING, requested.name)
        return PointerPreferenceResult(true, requested)
    }

    override fun sample(player: Player, plane: MenuPlane): MenuPoint? {
        val state = cursors[player.uniqueId] ?: return touch.sample(player, plane)
        return synchronized(state) { state.point }
    }

    fun poll(player: Player) {
        if (cursors.containsKey(player.uniqueId)) packets.requestRotationSample(player)
    }

    private fun consumeRotation(player: Player, yaw: Float, pitch: Float): Boolean {
        val state = cursors[player.uniqueId] ?: return false
        var requestRecentre = false
        synchronized(state) {
            if (state.recentring) {
                val yawClose = abs(wrapVirtualCursorDegrees((yaw - state.restoreYaw).toDouble())) <= RECENTRE_EPSILON
                val pitchClose = abs(pitch - state.restorePitch) <= RECENTRE_EPSILON
                if (yawClose && pitchClose) {
                    state.lastYaw = yaw
                    state.lastPitch = pitch
                    state.recentring = false
                }
                return true
            }
            val deltaYaw = wrapVirtualCursorDegrees((yaw - state.lastYaw).toDouble())
            val deltaPitch = (pitch - state.lastPitch).toDouble()
            state.lastYaw = yaw
            state.lastPitch = pitch
            state.point = updateVirtualCursor(state.point, deltaYaw, deltaPitch, state.canvas, settings)
            if (abs(pitch) >= RECENTRE_PITCH && abs(pitch - state.restorePitch) > RECENTRE_EPSILON) {
                state.recentring = true
                requestRecentre = true
            }
        }
        if (requestRecentre) Bukkit.getScheduler().runTask(plugin, Runnable { recentre(player, state) })
        return true
    }

    private fun consumePosition(player: Player): Boolean = cursors.containsKey(player.uniqueId)

    private fun acceptSyntheticTeleport(player: Player, teleportId: Int) {
        val state = cursors[player.uniqueId] ?: return
        state.activation.accept(teleportId)
        val exitAccepted = synchronized(state) {
            state.ending && state.exit.accept(teleportId)
        }
        if (exitAccepted && plugin.isEnabled) {
            Bukkit.getScheduler().runTask(plugin, Runnable { completeEnd(player, state) })
        }
    }

    private fun recentre(player: Player, expected: CursorState) {
        if (!player.isOnline || cursors[player.uniqueId] !== expected) return
        val rotation = synchronized(expected) { expected.restoreYaw to expected.restorePitch }
        packets.resetClientRotation(player, rotation.first, rotation.second)
    }

    private fun spawnCamera(player: Player, plane: MenuPlane): TextDisplay {
        // The global screen offset moves the menu, never the fixed camera.
        val location = player.eyeLocation.clone()
        val visibleYaw = Math.toDegrees(atan2(plane.normal.x, -plane.normal.z)).toFloat()
        // Keep the entity's exact yaw identical to the menu plane. Older
        // relative-rotation packets encode this value to the same byte, while
        // modern full position-sync packets preserve the same float. Storing a
        // bucket midpoint here would make the latter disagree with the plane.
        location.yaw = visibleYaw
        location.pitch = 0.0f
        // A living camera has separate body/head yaw and a pose-dependent eye
        // height. An empty Display has one exact position and one yaw/pitch,
        // so the client view remains perpendicular to the near-eye menu plane.
        val camera = player.world.spawn(location, TextDisplay::class.java) {
            it.isVisibleByDefault = false
            it.isPersistent = false
            it.setGravity(false)
            it.isInvulnerable = true
            it.isSilent = true
            it.text(Component.empty())
            it.backgroundColor = Color.fromARGB(0)
            it.isDefaultBackground = false
            it.billboard = Display.Billboard.FIXED
            it.interpolationDuration = 0
            it.teleportDuration = 0
        }
        camera.setRotation(location.yaw, location.pitch)
        entitySpawned()
        return camera
    }

    private fun clamp(point: MenuPoint, canvas: Canvas): MenuPoint =
        clampVirtualCursor(point, canvas, settings.clampMargin)

    override fun close() {
        Bukkit.getOnlinePlayers().forEach { player ->
            cursors[player.uniqueId]?.let { completeEndImmediately(player, it) }
        }
        cursors.values.forEach { it.camera.remove() }
        cursors.clear()
        packets.close()
    }

    private companion object {
        const val RECENTRE_PITCH = 85.0f
        const val RECENTRE_EPSILON = 2.0
        const val EXIT_ACK_TIMEOUT_TICKS = 20L
    }
}

internal fun updateVirtualCursor(
    point: MenuPoint,
    deltaYaw: Double,
    deltaPitch: Double,
    canvas: Canvas,
    settings: PointerSettings,
): MenuPoint {
    if (!deltaYaw.isFinite() || !deltaPitch.isFinite()) return clampVirtualCursor(point, canvas, settings.clampMargin)
    return clampVirtualCursor(
        MenuPoint(
            point.x + wrapVirtualCursorDegrees(deltaYaw) * settings.sensitivityX,
            point.y - deltaPitch * settings.sensitivityY,
        ),
        canvas,
        settings.clampMargin,
    )
}

private fun clampVirtualCursor(point: MenuPoint, canvas: Canvas, margin: Double): MenuPoint {
    val halfWidth = (canvas.width / 2.0 - margin).coerceAtLeast(0.0)
    val halfHeight = (canvas.height / 2.0 - margin).coerceAtLeast(0.0)
    return MenuPoint(point.x.coerceIn(-halfWidth, halfWidth), point.y.coerceIn(-halfHeight, halfHeight))
}

private fun wrapVirtualCursorDegrees(value: Double): Double {
    var result = value % 360.0
    if (result >= 180.0) result -= 360.0
    if (result < -180.0) result += 360.0
    return result
}

class TouchPointer : PointerSource {
    override fun sample(player: Player, plane: MenuPlane): MenuPoint? {
        val eye = player.eyeLocation
        val direction = eye.direction
        return plane.project(Ray(Vec3(eye.x, eye.y, eye.z), Vec3(direction.x, direction.y, direction.z)))
    }
}

fun PointerPolicy.displayName(): String = when (this) {
    PointerPolicy.PLAYER_CHOICE -> "player-choice"
    PointerPolicy.FORCE_TOUCH -> "force-touch"
    PointerPolicy.FORCE_MOUSE -> "force-mouse"
}

private fun storedPointerMode(value: String): PointerMode? = when (value.uppercase()) {
    "TOUCH", "CROSSHAIR" -> PointerMode.TOUCH
    "MOUSE", "CURSOR" -> PointerMode.MOUSE
    else -> null
}
