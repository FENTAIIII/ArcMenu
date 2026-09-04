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
    val scaleX: Double = 1.0, val scaleY: Double = 1.0,
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
data class ImageNode(
    override val properties: NodeProperties, val source: String,
    val width: Double? = null, val height: Double? = null,
    val opacity: Int = 255, val updateTicks: Int = -1,
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
) {
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
)

/** Retains the original author document; M1 never reserializes or executes it. */
data class MenuDocument(val definition: MenuDefinition, val source: String, val sourceName: String)
