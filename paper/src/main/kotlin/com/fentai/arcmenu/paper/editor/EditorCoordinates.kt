package com.fentai.arcmenu.paper.editor

import com.fentai.arcmenu.core.model.Canvas
import com.fentai.arcmenu.core.model.MenuPoint
import com.fentai.arcmenu.protocol.EditorProtocol
import kotlin.math.abs

/** Recomputes the accepted point from raw GUI and viewport values; clientX/clientY are diagnostics only. */
internal fun editorCoordinates(pointer: EditorProtocol.Pointer, canvas: Canvas): MenuPoint {
    val values = doubleArrayOf(
        pointer.mouseX(), pointer.mouseY(), pointer.viewportX(), pointer.viewportY(),
        pointer.viewportWidth(), pointer.viewportHeight(), pointer.clientX(), pointer.clientY(),
    )
    require(values.all(Double::isFinite)) { "坐标探针包含非有限值" }
    require(pointer.viewportWidth() in 32.0..32768.0 && pointer.viewportHeight() in 18.0..32768.0) { "编辑画布尺寸无效" }
    val x = (pointer.mouseX() - pointer.viewportX()) / pointer.viewportWidth() * canvas.width - canvas.width / 2.0
    val y = canvas.height / 2.0 - (pointer.mouseY() - pointer.viewportY()) / pointer.viewportHeight() * canvas.height
    require(x.isFinite() && y.isFinite()) { "服务端坐标换算失败" }
    require(abs(x) <= canvas.width * 2 && abs(y) <= canvas.height * 2) { "探针坐标超出编辑安全范围" }
    return MenuPoint(x, y)
}
