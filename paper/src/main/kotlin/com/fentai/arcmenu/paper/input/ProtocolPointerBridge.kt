package com.fentai.arcmenu.paper.input

import com.comphenix.protocol.PacketType
import com.comphenix.protocol.ProtocolLibrary
import com.comphenix.protocol.events.ListenerOptions
import com.comphenix.protocol.events.ListenerPriority
import com.comphenix.protocol.events.PacketAdapter
import com.comphenix.protocol.events.PacketContainer
import com.comphenix.protocol.events.PacketEvent
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** ProtocolLib boundary for the fixed-camera cursor protocol. */
internal class ProtocolPointerBridge(
    plugin: Plugin,
    private val rotation: (Player, Float, Float) -> Boolean,
    private val position: (Player) -> Boolean,
    private val syntheticTeleportAccepted: (Player, Int) -> Unit,
) : AutoCloseable {
    private val manager = ProtocolLibrary.getProtocolManager()
    private val nextSyntheticTeleportId = AtomicInteger(-1)
    private val syntheticTeleportIds = ConcurrentHashMap<UUID, MutableSet<Int>>()
    private val syntheticResponses = ConcurrentHashMap<UUID, AtomicInteger>()
    private val positionPackets by lazy { SyntheticPositionPacketFactory(PacketType.Play.Server.POSITION.packetClass) }
    private val gameModePackets by lazy { ClientGameModePacketFactory(PacketType.Play.Server.GAME_STATE_CHANGE.packetClass) }
    private val listener = object : PacketAdapter(
        plugin,
        ListenerPriority.HIGHEST,
        listOf(
            PacketType.Play.Client.LOOK,
            PacketType.Play.Client.POSITION_LOOK,
            PacketType.Play.Client.POSITION,
            PacketType.Play.Client.TELEPORT_ACCEPT,
            PacketType.Play.Client.USE_ENTITY,
        ),
        ListenerOptions.ASYNC,
    ) {
        override fun onPacketReceiving(event: PacketEvent) {
            if (event.isPlayerTemporary) return
            val consumed = when (event.packetType) {
                PacketType.Play.Client.LOOK -> consumeRotation(event)
                PacketType.Play.Client.POSITION_LOOK -> {
                    val synthetic = claimSyntheticResponse(event.player)
                    val cursor = consumeRotation(event)
                    synthetic || cursor
                }
                PacketType.Play.Client.POSITION -> position(event.player)
                PacketType.Play.Client.TELEPORT_ACCEPT -> consumeSyntheticTeleport(event)
                PacketType.Play.Client.USE_ENTITY -> consumeSelfInteraction(event)
                else -> false
            }
            if (consumed) event.isCancelled = true
        }
    }

    init {
        manager.addPacketListener(listener)
    }

    /**
     * The camera packet has no no-argument constructor. Let ProtocolLib unwrap the Bukkit entity
     * and invoke ClientboundSetCameraPacket(Entity) instead of creating an empty packet first.
     */
    fun setCamera(player: Player, target: Entity) {
        val constructor = manager.createPacketConstructor(PacketType.Play.Server.CAMERA, target)
        val packet = constructor.createPacket(target)
        manager.sendServerPacket(player, packet, false)
    }

    /** Spectator rendering suppresses both held items and empty player arms, and is client-only here. */
    fun hideFirstPersonHands(player: Player) = sendClientGameMode(player, 3)

    fun restoreClientGameMode(player: Player) = sendClientGameMode(player, when (player.gameMode) {
        GameMode.SURVIVAL -> 0
        GameMode.CREATIVE -> 1
        GameMode.ADVENTURE -> 2
        GameMode.SPECTATOR -> 3
    })

    /**
     * An external camera makes vanilla LocalPlayer.sendPosition() stop sending natural LOOK packets.
     * A zero relative position sync asks the client for an immediate PosRot reply without moving its
     * player or camera. Its negative teleport id and reply are consumed by this bridge.
     */
    fun requestRotationSample(player: Player) {
        sendSyntheticPosition(player, null, null)
    }

    /** Re-centres only the hidden client player rotation; the fixed camera and Bukkit player are untouched. */
    fun resetClientRotation(
        player: Player,
        yaw: Float,
        pitch: Float,
        beforeSend: (Int) -> Unit = {},
    ): Int = requireNotNull(sendSyntheticPosition(player, yaw, pitch, beforeSend))

    /** Replaces the client's predicted local-player position with the authoritative server state. */
    fun synchronizeClientPlayer(player: Player, beforeSend: (Int) -> Unit = {}): Int {
        val location = player.location
        return requireNotNull(sendSyntheticPosition(player, location.yaw, location.pitch, beforeSend, location))
    }

    private fun consumeRotation(event: PacketEvent): Boolean {
        val fields = event.packet.float
        return fields.size() >= 2 && rotation(event.player, fields.read(0), fields.read(1))
    }

    private fun consumeSyntheticTeleport(event: PacketEvent): Boolean {
        val integers = event.packet.integers
        if (integers.size() < 1) return false
        val ids = syntheticTeleportIds[event.player.uniqueId] ?: return false
        val id = integers.read(0)
        if (!ids.remove(id)) return false
        syntheticResponses.computeIfAbsent(event.player.uniqueId) { AtomicInteger() }.incrementAndGet()
        syntheticTeleportAccepted(event.player, id)
        return true
    }

    private fun claimSyntheticResponse(player: Player): Boolean {
        val counter = syntheticResponses[player.uniqueId] ?: return false
        while (true) {
            val value = counter.get()
            if (value <= 0) return false
            if (counter.compareAndSet(value, value - 1)) return true
        }
    }

    private fun consumeSelfInteraction(event: PacketEvent): Boolean {
        if (!position(event.player)) return false
        val integers = event.packet.integers
        return integers.size() >= 1 && integers.read(0) == event.player.entityId
    }

    private fun sendSyntheticPosition(
        player: Player,
        yaw: Float?,
        pitch: Float?,
        beforeSend: (Int) -> Unit = {},
        absolutePosition: Location? = null,
    ): Int? {
        val ids = syntheticTeleportIds.computeIfAbsent(player.uniqueId) { ConcurrentHashMap.newKeySet() }
        // A non-responsive client must not create an unbounded pending-id collection.
        // Explicit reset/restore packets still have to pass so cleanup cannot leave a stale view.
        if (yaw == null && ids.size >= MAX_PENDING_TELEPORTS) return null
        val id = nextSyntheticTeleportId.getAndDecrement()
        ids += id
        try {
            val handle = absolutePosition?.let {
                positionPackets.createAbsolute(id, it.x, it.y, it.z, requireNotNull(yaw), requireNotNull(pitch))
            } ?: positionPackets.create(id, yaw, pitch)
            beforeSend(id)
            manager.sendServerPacket(player, PacketContainer(PacketType.Play.Server.POSITION, handle), false)
        } catch (error: Throwable) {
            ids -= id
            throw error
        }
        return id
    }

    private fun sendClientGameMode(player: Player, id: Int) {
        val handle = gameModePackets.create(id)
        manager.sendServerPacket(player, PacketContainer(PacketType.Play.Server.GAME_STATE_CHANGE, handle), false)
    }

    override fun close() {
        manager.removePacketListener(listener)
        syntheticTeleportIds.clear()
        syntheticResponses.clear()
    }

    private companion object {
        const val MAX_PENDING_TELEPORTS = 128
    }
}

/** Supports both the 1.21.1 seven-field packet and the newer PositionMoveRotation packet. */
internal class SyntheticPositionPacketFactory(packetClass: Class<*>) {
    private val packetConstructor: Constructor<*>
    private val legacy: Boolean
    private val changeConstructor: Constructor<*>?
    private val vectorConstructor: Constructor<*>?
    private val zeroVector: Any?
    private val allRelatives: Set<Any>
    private val positionRelatives: Set<Any>

    init {
        val legacyConstructor = packetClass.constructors.firstOrNull { constructor ->
            val types = constructor.parameterTypes
            types.size == 7 && types.take(3).all { it == Double::class.javaPrimitiveType } &&
                types[3] == Float::class.javaPrimitiveType && types[4] == Float::class.javaPrimitiveType &&
                Set::class.java.isAssignableFrom(types[5]) && types[6] == Int::class.javaPrimitiveType
        }
        val modernConstructor = packetClass.constructors.firstOrNull { constructor ->
            val types = constructor.parameterTypes
            types.size == 3 && types[0] == Int::class.javaPrimitiveType && Set::class.java.isAssignableFrom(types[2])
        }
        packetConstructor = legacyConstructor ?: modernConstructor
            ?: error("不支持的 ClientboundPlayerPositionPacket 构造器: ${packetClass.name}")
        legacy = legacyConstructor != null
        val relativeIndex = if (legacy) 5 else 2
        val relativeClass = findEnumClass(packetConstructor.genericParameterTypes[relativeIndex])
            ?: error("无法识别 ${packetClass.name} 的相对移动枚举")
        val constants = relativeClass.enumConstants?.map { it as Any }
            ?: error("${relativeClass.name} 不是相对移动枚举")
        allRelatives = constants.toSet()
        // Both enums place X/Y/Z first and Y_ROT/X_ROT at 3/4. Newer releases add
        // ROTATE_DELTA at 8; omit it while resetting rotation so velocity is not rotated.
        positionRelatives = constants.filterNot { (it as Enum<*>).ordinal in setOf(3, 4, 8) }.toSet()

        if (legacy) {
            changeConstructor = null
            vectorConstructor = null
            zeroVector = null
        } else {
            val changeClass = packetConstructor.parameterTypes[1]
            changeConstructor = changeClass.constructors.firstOrNull { constructor ->
                val types = constructor.parameterTypes
                types.size == 4 && types[0] == types[1] &&
                    types[2] == Float::class.javaPrimitiveType && types[3] == Float::class.javaPrimitiveType
            } ?: error("无法识别 ${changeClass.name} 构造器")
            val vectorClass = changeConstructor.parameterTypes[0]
            vectorConstructor = vectorClass.getConstructor(
                Double::class.javaPrimitiveType,
                Double::class.javaPrimitiveType,
                Double::class.javaPrimitiveType,
            )
            zeroVector = vectorConstructor.newInstance(0.0, 0.0, 0.0)
        }
    }

    fun create(id: Int, yaw: Float?, pitch: Float?): Any {
        require((yaw == null) == (pitch == null)) { "yaw 与 pitch 必须同时为空或同时提供" }
        val preserveRotation = yaw == null
        val yRot = yaw ?: 0.0f
        val xRot = pitch ?: 0.0f
        val relatives = if (preserveRotation) allRelatives else positionRelatives
        return if (legacy) {
            packetConstructor.newInstance(0.0, 0.0, 0.0, yRot, xRot, relatives, id)
        } else {
            val change = changeConstructor!!.newInstance(zeroVector, zeroVector, yRot, xRot)
            packetConstructor.newInstance(id, change, relatives)
        }
    }

    /** Absolute feet position and rotation used before returning the camera to the local player. */
    fun createAbsolute(
        id: Int,
        x: Double,
        y: Double,
        z: Double,
        yaw: Float,
        pitch: Float,
    ): Any = if (legacy) {
        packetConstructor.newInstance(x, y, z, yaw, pitch, emptySet<Any>(), id)
    } else {
        val position = vectorConstructor!!.newInstance(x, y, z)
        val change = changeConstructor!!.newInstance(position, zeroVector, yaw, pitch)
        packetConstructor.newInstance(id, change, emptySet<Any>())
    }

    private fun findEnumClass(type: Type): Class<*>? = when (type) {
        is Class<*> -> type.takeIf { it.isEnum }
        is ParameterizedType -> type.actualTypeArguments.firstNotNullOfOrNull(::findEnumClass)
        is WildcardType -> (type.upperBounds + type.lowerBounds).firstNotNullOfOrNull(::findEnumClass)
        else -> null
    }
}

/** Builds ClientboundGameEventPacket(CHANGE_GAME_MODE, mode) without versioned NMS imports. */
private class ClientGameModePacketFactory(private val packetClass: Class<*>) {
    private val packetConstructor = packetClass.constructors.firstOrNull { constructor ->
        val types = constructor.parameterTypes
        types.size == 2 && types[1] == Float::class.javaPrimitiveType
    } ?: error("无法识别 ${packetClass.name} 构造器")
    private val changeGameMode = findChangeGameMode(packetConstructor.parameterTypes[0])

    fun create(id: Int): Any = packetConstructor.newInstance(changeGameMode, id.toFloat())

    private fun findChangeGameMode(eventClass: Class<*>): Any {
        runCatching { packetClass.getField("CHANGE_GAME_MODE").get(null) }.getOrNull()?.let { return it }
        val idField = eventClass.declaredFields.firstOrNull {
            !Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType
        } ?: error("无法识别 ${eventClass.name} 的事件 ID")
        check(idField.trySetAccessible()) { "无法读取 ${eventClass.name} 的事件 ID" }
        return packetClass.declaredFields.asSequence()
            .filter { Modifier.isStatic(it.modifiers) && it.type == eventClass }
            .onEach { check(it.trySetAccessible()) { "无法读取 ${packetClass.name} 的游戏事件" } }
            .map { it.get(null) }
            .firstOrNull { idField.getInt(it) == 3 }
            ?: error("无法定位 ${packetClass.name}.CHANGE_GAME_MODE")
    }
}
