package com.fentai.arcmenu.paper.render

import com.fentai.arcmenu.paper.resource.NineSlicePart

internal data class TooltipSkinCell(
    val centerX: Double,
    val centerY: Double,
    val width: Double,
    val height: Double,
)

internal data class TooltipLayout(
    val textOriginX: Double,
    val textOriginY: Double,
    val cells: Map<NineSlicePart, TooltipSkinCell>,
)

/** Pins a selected outer corner to the pointer offset, independent of the tooltip's dynamic size. */
internal object TooltipSkinLayout {
    fun layout(
        cornerX: Double,
        cornerY: Double,
        style: TooltipStyle,
        skin: TooltipSkin,
        box: TooltipBox,
    ): TooltipLayout {
        val pixelX = style.size / 9.0 * skin.scaleX
        val pixelY = style.size / 9.0 * skin.scaleY
        val totalWidth = box.widthPixels * pixelX
        val totalHeight = box.heightPixels * pixelY
        val bounds = bounds(cornerX, cornerY, totalWidth, totalHeight, style.anchor)

        val extraWidth = box.widthPixels - box.contentWidthPixels - skin.padding.left - skin.padding.right
        val extraHeight = box.heightPixels - box.contentHeightPixels - skin.padding.top - skin.padding.bottom
        val textOriginX = bounds.left +
            (skin.padding.left + extraWidth / 2.0 + box.contentWidthPixels / 2.0) * pixelX +
            skin.textOffsetX
        val textOriginY = bounds.bottom +
            (skin.padding.bottom + extraHeight / 2.0) * pixelY +
            skin.textOffsetY

        val skinLeft = bounds.left + skin.offsetX
        val skinRight = bounds.right + skin.offsetX
        val skinTop = bounds.top + skin.offsetY
        val skinBottom = bounds.bottom + skin.offsetY
        val xEdges = doubleArrayOf(
            skinLeft,
            skinLeft + skin.border * pixelX,
            skinRight - skin.border * pixelX,
            skinRight,
        )
        val yEdges = doubleArrayOf(
            skinTop,
            skinTop - skin.border * pixelY,
            skinBottom + skin.border * pixelY,
            skinBottom,
        )
        val overlapX = skin.seamOverlapX * pixelX / 2.0
        val overlapY = skin.seamOverlapY * pixelY / 2.0

        val cells = NineSlicePart.entries.associateWith { part ->
            var x0 = xEdges[part.column]
            var x1 = xEdges[part.column + 1]
            var y0 = yEdges[part.row]
            var y1 = yEdges[part.row + 1]
            if (part.column > 0) x0 -= overlapX
            if (part.column < 2) x1 += overlapX
            if (part.row > 0) y0 += overlapY
            if (part.row < 2) y1 -= overlapY
            val columnOffset = when (part.column) {
                0 -> skin.columnOffsets.first
                1 -> skin.columnOffsets.second
                else -> skin.columnOffsets.third
            } * pixelX
            val rowOffset = when (part.row) {
                0 -> skin.rowOffsets.first
                1 -> skin.rowOffsets.second
                else -> skin.rowOffsets.third
            } * pixelY
            TooltipSkinCell(
                centerX = (x0 + x1) / 2.0 + columnOffset + skin.glyphOffsetX * pixelX,
                centerY = (y0 + y1) / 2.0 + rowOffset + skin.glyphOffsetY * pixelY,
                width = x1 - x0,
                height = y0 - y1,
            )
        }
        return TooltipLayout(textOriginX, textOriginY, cells)
    }

    fun plainTextOrigin(
        cornerX: Double,
        cornerY: Double,
        style: TooltipStyle,
        content: TooltipContentSize,
    ): Pair<Double, Double> {
        val pixel = style.size / 9.0
        val bounds = bounds(
            cornerX,
            cornerY,
            content.widthPixels * pixel,
            content.heightPixels * pixel,
            style.anchor,
        )
        return (bounds.left + bounds.right) / 2.0 to bounds.bottom
    }

    private fun bounds(
        cornerX: Double,
        cornerY: Double,
        width: Double,
        height: Double,
        anchor: TooltipAnchor,
    ): Bounds = when (anchor) {
        TooltipAnchor.TOP_LEFT -> Bounds(cornerX, cornerX + width, cornerY, cornerY - height)
        TooltipAnchor.TOP_RIGHT -> Bounds(cornerX - width, cornerX, cornerY, cornerY - height)
        TooltipAnchor.BOTTOM_LEFT -> Bounds(cornerX, cornerX + width, cornerY + height, cornerY)
        TooltipAnchor.BOTTOM_RIGHT -> Bounds(cornerX - width, cornerX, cornerY + height, cornerY)
    }

    private data class Bounds(
        val left: Double,
        val right: Double,
        val top: Double,
        val bottom: Double,
    )
}
