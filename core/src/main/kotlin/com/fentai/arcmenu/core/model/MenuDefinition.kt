package com.fentai.arcmenu.core.model

import com.fentai.arcmenu.core.behavior.*

data class Vec3(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0) {
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(factor: Double) = Vec3(x * factor, y * factor, z * factor)
    fun dot(other: Vec3) = x * other.x + y * other.y + z * other.z
}

data class Transform(
    val offset: Vec3 = Vec3(), val rotation: Vec3 = Vec3(),
    val scaleX: Double = 1.0, val scaleY: Double = 1.0, val scaleZ: Double = 1.0,
)

data class Canvas(
    val width: Double = 320.0, val height: Double = 180.0,
    val pixelsPerBlock: Double = 100.0, val distance: Double = 3.0,
)

data class NodeProperties(val id: String, val transform: Transform = Transform(), val visible: Boolean = true)

sealed interface VisualNode { val properties: NodeProperties }
data class GroupNode(override val properties: NodeProperties, val children: List<VisualNode>) : VisualNode
data class RectangleNode(
    override val properties: NodeProperties, val width: Double, val height: Double, val argb: Int,
) : VisualNode
data class FrameNode(
    override val properties: NodeProperties, val width: Double, val height: Double,
    val thickness: Double, val argb: Int,
) : VisualNode
data class TextNode(
    override val properties: NodeProperties, val content: String, val size: Double = 10.0,
    val font: String = "minecraft:default", val opacity: Int = 255, val lineWidth: Int = 200,
    val alignment: String = "center", val updateTicks: Int = -1,
) : VisualNode
data class ImageNode @JvmOverloads constructor(
    override val properties: NodeProperties, val source: String,
    val width: Double? = null, val height: Double? = null,
    val opacity: Int = 255, val updateTicks: Int = -1,
    val color: Int? = null,
    val animated: Boolean = false, val speed: Double = 1.0,
) : VisualNode
data class ItemNode(
    override val properties: NodeProperties, val material: String, val context: String = "GUI",
) : VisualNode
data class BlockNode(override val properties: NodeProperties, val blockData: String) : VisualNode

data class MenuPoint(val x: Double, val y: Double)

/** Independent screen-aligned region. Deliberately has no parent or transform. */
data class InteractionRegion(
    val id: String, val x: Double, val y: Double, val width: Double, val height: Double,
    val priority: Int = 0, val tooltip: List<String> = emptyList(),
    val tooltipUpdateTicks: Int = -1,
    val condition: ConditionExpression? = null,
    val actions: ClickActions = ClickActions(),
    val deny: List<MenuAction> = emptyList(),
    val onHover: List<ActionReaction> = emptyList(),
    val onLeave: List<ActionReaction> = emptyList(),
) {
    /** Preserves the original constructor used by compiled menu integrations. */
    constructor(
        id: String, x: Double, y: Double, width: Double, height: Double,
        priority: Int = 0, tooltip: List<String> = emptyList(),
        tooltipUpdateTicks: Int = -1,
        condition: ConditionExpression? = null,
        actions: ClickActions = ClickActions(),
        deny: List<MenuAction> = emptyList(),
    ) : this(id, x, y, width, height, priority, tooltip, tooltipUpdateTicks, condition, actions, deny, emptyList(), emptyList())

    fun contains(point: MenuPoint): Boolean =
        point.x >= x - width / 2 && point.x <= x + width / 2 &&
            point.y >= y - height / 2 && point.y <= y + height / 2
}

data class MenuDefinition(
    val id: String, val canvas: Canvas,
    val frontend: List<VisualNode>, val backend: List<InteractionRegion>,
    val permission: String = "",
    val events: MenuEvents = MenuEvents(),
    val openCommands: List<String> = emptyList(),
    val mainMenu: Boolean = false,
    val permissionDeniedMenu: String = "",
) {
    /** Preserves the original constructor used by compiled menu integrations. */
    constructor(
        id: String, canvas: Canvas,
        frontend: List<VisualNode>, backend: List<InteractionRegion>,
        permission: String = "", events: MenuEvents = MenuEvents(),
        openCommands: List<String> = emptyList(), mainMenu: Boolean = false,
    ) : this(id, canvas, frontend, backend, permission, events, openCommands, mainMenu, "")

    /** Keeps the former JVM copy method without adding another Kotlin copy overload. */
    @JvmName("copy")
    fun copyLegacy(
        id: String, canvas: Canvas,
        frontend: List<VisualNode>, backend: List<InteractionRegion>,
        permission: String, events: MenuEvents,
        openCommands: List<String>, mainMenu: Boolean,
    ) = copy(id, canvas, frontend, backend, permission, events, openCommands, mainMenu, permissionDeniedMenu)

    companion object {
        /** Retains default-argument copying for integrations compiled before this field existed. */
        @JvmStatic
        @JvmName("copy\$default")
        @Suppress("UNUSED_PARAMETER")
        fun copyLegacyDefaults(
            menu: MenuDefinition,
            id: String?, canvas: Canvas?,
            frontend: List<VisualNode>?, backend: List<InteractionRegion>?,
            permission: String?, events: MenuEvents?,
            openCommands: List<String>?, mainMenu: Boolean,
            mask: Int, marker: Any?,
        ) = menu.copyLegacy(
            if (mask and 1 != 0) menu.id else requireNotNull(id),
            if (mask and 2 != 0) menu.canvas else requireNotNull(canvas),
            if (mask and 4 != 0) menu.frontend else requireNotNull(frontend),
            if (mask and 8 != 0) menu.backend else requireNotNull(backend),
            if (mask and 16 != 0) menu.permission else requireNotNull(permission),
            if (mask and 32 != 0) menu.events else requireNotNull(events),
            if (mask and 64 != 0) menu.openCommands else requireNotNull(openCommands),
            if (mask and 128 != 0) menu.mainMenu else mainMenu,
        )
    }
}

/** Retains the original author document; M1 never reserializes or executes it. */
data class MenuDocument(val definition: MenuDefinition, val source: String, val sourceName: String)
