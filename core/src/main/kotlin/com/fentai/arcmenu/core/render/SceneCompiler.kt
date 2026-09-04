package com.fentai.arcmenu.core.render

import com.fentai.arcmenu.core.animation.AnimationSnapshot
import com.fentai.arcmenu.core.geometry.Transforms
import com.fentai.arcmenu.core.model.*
import org.joml.Matrix4d
import org.joml.Matrix4dc
import kotlin.math.abs
import kotlin.math.min

sealed interface Primitive {
    val id: String
    val transform: Matrix4dc
}
data class Quad(override val id: String, override val transform: Matrix4dc, val width: Double, val height: Double, val argb: Int) : Primitive
data class Text(override val id: String, override val transform: Matrix4dc, val node: TextNode) : Primitive
data class Image(override val id: String, override val transform: Matrix4dc, val node: ImageNode) : Primitive
data class Item(override val id: String, override val transform: Matrix4dc, val node: ItemNode) : Primitive
data class Block(override val id: String, override val transform: Matrix4dc, val node: BlockNode) : Primitive

/** Compiles frontend only. Backend rectangles never pass through this traversal. */
class SceneCompiler {
    fun compile(nodes: List<VisualNode>, animation: AnimationSnapshot = AnimationSnapshot()): List<Primitive> = buildList {
        fun visit(node: VisualNode, parent: Matrix4dc) {
            if (!node.properties.visible) return
            val id = node.properties.id
            val transform = animation.nodeTransforms[id] ?: node.properties.transform
            val matrix = Matrix4d(parent).mul(Transforms.local(transform))
            when (node) {
                is GroupNode -> node.children.forEach { visit(it, matrix) }
                is RectangleNode -> add(Quad(id, matrix, node.width, node.height, node.argb))
                is TextNode -> add(Text(id, matrix, node))
                is ImageNode -> add(Image(id, matrix, node))
                is ItemNode -> add(Item(id, modelMatrix(matrix, transform), node))
                is BlockNode -> add(Block(id, modelMatrix(matrix, transform), node))
                is FrameNode -> {
                    val t = node.thickness
                    val verticalEdge = node.height * FRAME_VERTICAL_EDGE_FACTOR
                    add(Quad("$id/top", Matrix4d(matrix).translate(0.0, verticalEdge, 0.0), node.width, t, node.argb))
                    add(Quad("$id/bottom", Matrix4d(matrix).translate(0.0, -verticalEdge, 0.0), node.width, t, node.argb))

                    // A TextDisplay space background is affine, but its visible bounds are not centred on
                    // its entity origin. Keep vertical edges on the exact same path as an authored line
                    // rotated by 90 degrees. The edge factors come from the in-game real_border1 calibration
                    // and scale proportionally with the requested frame dimensions.
                    add(Quad("$id/left", Matrix4d(matrix)
                        .translate(-node.width * FRAME_LEFT_EDGE_FACTOR, 0.0, 0.0)
                        .rotateZ(Math.toRadians(90.0)), node.height, t, node.argb))
                    add(Quad("$id/right", Matrix4d(matrix)
                        .translate(node.width * FRAME_RIGHT_EDGE_FACTOR, 0.0, 0.0)
                        .rotateZ(Math.toRadians(90.0)), node.height, t, node.argb))
                }
            }
        }
        val root = Transforms.local(animation.rootTransform)
        nodes.forEach { visit(it, root) }
    }

    private fun modelMatrix(matrix: Matrix4dc, transform: Transform): Matrix4dc {
        // The author-facing transform has 2D scale controls. Give 3D models a matching local depth so a
        // square block does not collapse to a one-logical-unit slab; non-uniform models use the smaller axis.
        val depth = min(abs(transform.scaleX), abs(transform.scaleY))
        return Matrix4d(matrix).scale(1.0, 1.0, depth)
    }

    private companion object {
        const val FRAME_VERTICAL_EDGE_FACTOR = 27.78 / 55.0
        const val FRAME_LEFT_EDGE_FACTOR = 55.34 / 110.0
        const val FRAME_RIGHT_EDGE_FACTOR = 55.55 / 110.0
    }
}
