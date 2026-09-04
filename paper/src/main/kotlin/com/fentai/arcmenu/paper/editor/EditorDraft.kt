package com.fentai.arcmenu.paper.editor

import com.fentai.arcmenu.core.geometry.HitTester
import com.fentai.arcmenu.core.geometry.Transforms
import com.fentai.arcmenu.core.config.VisualTemplate
import com.fentai.arcmenu.core.model.*
import com.fentai.arcmenu.protocol.EditorProtocol
import org.joml.Matrix3d
import org.joml.Matrix4d
import org.joml.Matrix4dc
import org.joml.Quaterniond
import org.joml.Vector3d
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

internal data class NodeGeometry(val id: String, val x: Double, val y: Double, val width: Double, val height: Double)

internal class EditorDraft(
    val owner: UUID,
    val file: Path,
    val menuId: String,
    source: String,
    definition: MenuDefinition,
    private val validate: (MenuDefinition) -> Unit,
    private val parseSaved: (String) -> MenuDefinition,
) {
    private data class DraftState(val source: String, val definition: MenuDefinition)
    private val undo = ArrayDeque<DraftState>()
    private val redo = ArrayDeque<DraftState>()
    private var persistedState = DraftState(source, definition)
    private var currentState = persistedState
    private var persistedFingerprint = source
    private var activeGestureKey: String? = null

    var revision: Long = 0
        private set
    var saved: Boolean = true
        private set
    var activeTab: Byte = EditorProtocol.TAB_FRONTEND

    val definition: MenuDefinition get() = currentState.definition
    val dirty: Boolean get() = currentState.source != persistedState.source

    fun switchTab(tab: Byte) {
        require(tab == EditorProtocol.TAB_FRONTEND || tab == EditorProtocol.TAB_BACKEND) { "未知编辑器 Tab" }
        activeGestureKey = null
        activeTab = tab
    }

    fun move(
        expectedRevision: Long,
        tab: Byte,
        id: String,
        x: Double,
        y: Double,
        gestureId: Long = 0,
        finalUpdate: Boolean = true,
    ): NodeGeometry {
        requireRevision(expectedRevision)
        require(gestureId >= 0 && (gestureId != 0L || finalUpdate)) { "拖动手势编号无效" }
        val before = definition
        validatePoint(before.canvas, x, y)
        val next = when (tab) {
            EditorProtocol.TAB_FRONTEND -> before.copy(frontend = moveFrontend(before.frontend, id, x, y))
            EditorProtocol.TAB_BACKEND -> before.copy(backend = before.backend.map { region ->
                if (region.id == id) region.copy(x = x, y = y) else region
            }.also { require(it.any { region -> region.id == id }) { "后端区域不存在: $id" } })
            else -> throw IllegalArgumentException("未知编辑器 Tab")
        }
        val nextSource = if (tab == EditorProtocol.TAB_FRONTEND) {
            val node = findVisual(next.frontend, id) ?: error("前端元素不存在: $id")
            val offset = node.properties.transform.offset
            YamlScalarPatcher.frontendOffset(currentState.source, id, offset.x, offset.y)
        } else {
            YamlScalarPatcher.backendRegion(currentState.source, next.backend.first { it.id == id })
        }
        val gestureKey = gestureId.takeIf { it != 0L }?.let { "move:$tab:$id:$it" }
        if (nextSource == currentState.source) {
            require(finalUpdate && gestureKey != null && activeGestureKey == gestureKey) { "操作没有产生变化" }
            activeGestureKey = null
            return geometry(id, tab)
        }
        commitSource(nextSource, next, gestureKey)
        if (finalUpdate) activeGestureKey = null
        return geometry(id, tab)
    }

    fun resize(
        expectedRevision: Long,
        tab: Byte,
        id: String,
        pointerX: Double,
        pointerY: Double,
        gestureId: Long = 0,
        finalUpdate: Boolean = true,
    ): NodeGeometry {
        requireRevision(expectedRevision)
        require(gestureId >= 0 && (gestureId != 0L || finalUpdate)) { "缩放手势编号无效" }
        val before = definition
        val current = geometry(id, tab)
        val width = max(1.0, abs(pointerX - current.x) * 2.0)
        val height = max(1.0, abs(pointerY - current.y) * 2.0)
        require(width <= before.canvas.width * 4 && height <= before.canvas.height * 4) { "元素尺寸超出允许范围" }
        val next = when (tab) {
            EditorProtocol.TAB_FRONTEND -> before.copy(frontend = resizeFrontend(before.frontend, id, width, height))
            EditorProtocol.TAB_BACKEND -> before.copy(backend = before.backend.map {
                if (it.id == id) it.copy(width = width, height = height) else it
            }.also { require(it.any { region -> region.id == id }) { "后端区域不存在: $id" } })
            else -> throw IllegalArgumentException("未知编辑器 Tab")
        }
        val nextSource = if (tab == EditorProtocol.TAB_FRONTEND) {
            val target = findVisual(next.frontend, id) ?: error("前端元素不存在: $id")
            YamlScalarPatcher.frontendScale(currentState.source, id,
                target.properties.transform.scaleX, target.properties.transform.scaleY)
        } else {
            YamlScalarPatcher.backendRegion(currentState.source, next.backend.first { it.id == id })
        }
        val gestureKey = gestureId.takeIf { it != 0L }?.let { "resize:$tab:$id:$it" }
        if (nextSource == currentState.source) {
            require(finalUpdate && gestureKey != null && activeGestureKey == gestureKey) { "操作没有产生变化" }
            activeGestureKey = null
            return geometry(id, tab)
        }
        commitSource(nextSource, next, gestureKey)
        if (finalUpdate) activeGestureKey = null
        return geometry(id, tab)
    }

    fun create(expectedRevision: Long, tab: Byte, kind: Byte, parentId: String, initialSource: String = ""): String {
        requireRevision(expectedRevision)
        val result = YamlDocumentEditor.create(currentState.source, tab, kind, parentId, initialSource)
        commitSource(result.source)
        return result.id
    }

    fun delete(expectedRevision: Long, tab: Byte, id: String) {
        requireRevision(expectedRevision)
        commitSource(YamlDocumentEditor.delete(currentState.source, tab, id))
    }

    fun deleteMany(expectedRevision: Long, tab: Byte, ids: List<String>) {
        requireRevision(expectedRevision)
        commitSource(YamlDocumentEditor.deleteMany(currentState.source, tab, ids))
    }

    fun duplicate(
        expectedRevision: Long,
        tab: Byte,
        ids: List<String>,
        targetParentId: String,
        preserveParents: Boolean,
    ): List<String> {
        requireRevision(expectedRevision)
        val result = YamlDocumentEditor.duplicate(currentState.source, tab, ids, targetParentId, preserveParents)
        commitSource(result.source)
        return result.ids
    }

    fun group(expectedRevision: Long, ids: List<String>): String {
        requireRevision(expectedRevision)
        val result = YamlDocumentEditor.group(currentState.source, ids)
        commitSource(result.source)
        return result.id
    }

    fun reorder(expectedRevision: Long, tab: Byte, ids: List<String>, beforeId: String) {
        requireRevision(expectedRevision)
        commitSource(YamlDocumentEditor.reorder(currentState.source, tab, ids, beforeId))
    }

    fun reparent(expectedRevision: Long, ids: List<String>, targetParentId: String, beforeId: String) {
        requireRevision(expectedRevision)
        commitSource(YamlDocumentEditor.reparent(currentState.source, ids, targetParentId, beforeId))
    }

    fun setProperty(
        expectedRevision: Long,
        tab: Byte,
        id: String,
        key: String,
        value: String,
        gestureId: Long = 0,
        finalUpdate: Boolean = true,
    ) {
        requireRevision(expectedRevision)
        require(gestureId >= 0 && (gestureId != 0L || finalUpdate)) { "属性拖动手势编号无效" }
        val nextSource = YamlDocumentEditor.setProperty(currentState.source, tab, id, key, value)
        val gestureKey = gestureId.takeIf { it != 0L }?.let { "property:$tab:$id:$key:$it" }
        if (nextSource == currentState.source) {
            require(finalUpdate && gestureKey != null && activeGestureKey == gestureKey) { "操作没有产生变化" }
            activeGestureKey = null
            return
        }
        commitSource(nextSource, gestureKey = gestureKey)
        if (finalUpdate) activeGestureKey = null
    }

    fun instantiate(expectedRevision: Long, template: VisualTemplate): String {
        requireRevision(expectedRevision)
        val result = YamlDocumentEditor.instantiate(currentState.source, template)
        commitSource(result.source)
        return result.id
    }

    fun templateSource(expectedRevision: Long, nodeId: String, templateId: String): String {
        requireRevision(expectedRevision)
        return YamlDocumentEditor.template(currentState.source, nodeId, templateId)
    }

    fun undo(expectedRevision: Long) {
        requireRevision(expectedRevision)
        activeGestureKey = null
        val previous = undo.pollLast() ?: throw IllegalArgumentException("没有可撤销的操作")
        redo.addLast(currentState)
        currentState = previous
        revision++
        saved = !dirty
    }

    fun redo(expectedRevision: Long) {
        requireRevision(expectedRevision)
        activeGestureKey = null
        val next = redo.pollLast() ?: throw IllegalArgumentException("没有可重做的操作")
        undo.addLast(currentState)
        currentState = next
        revision++
        saved = !dirty
    }

    fun save(expectedRevision: Long): EditorSaveResult {
        requireRevision(expectedRevision)
        activeGestureKey = null
        val disk = Files.readString(file)
        require(disk == persistedFingerprint) { "菜单文件已被外部修改；请关闭编辑器后重新打开，当前草稿未覆盖磁盘" }
        if (!dirty) {
            saved = true
            return EditorSaveResult(false)
        }
        val nextSource = currentState.source
        val reparsed = parseSaved(nextSource)
        require(reparsed == definition) { "保存后的 YAML 语义与草稿不一致，已拒绝写盘" }
        val backup = file.resolveSibling("${file.fileName}.arcmenu-backup")
        if (!Files.exists(backup)) {
            Files.writeString(backup, persistedFingerprint)
        }
        val temp = file.resolveSibling(".${file.fileName}.arcmenu-${UUID.randomUUID()}.tmp")
        Files.writeString(temp, nextSource)
        try {
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
        persistedFingerprint = nextSource
        persistedState = DraftState(nextSource, reparsed)
        currentState = persistedState
        saved = true
        return EditorSaveResult(true, file.fileName.toString(), backup.fileName.toString())
    }

    fun snapshot(
        serverVersion: String,
        images: List<EditorProtocol.ImageSnapshot> = emptyList(),
        templates: List<EditorProtocol.TemplateSnapshot> = emptyList(),
    ): EditorProtocol.SnapshotPacket {
        val propertyIndex = YamlDocumentEditor.propertyIndex(currentState.source)
        val typeIndex = YamlDocumentEditor.typeIndex(currentState.source)
        val frontend = mutableListOf<EditorProtocol.NodeSnapshot>()
        fun visit(nodes: List<VisualNode>, parentId: String, parent: Matrix4dc) {
            nodes.forEach { node ->
                val matrix = Matrix4d(parent).mul(Transforms.local(node.properties.transform))
                val (intrinsicWidth, intrinsicHeight) = intrinsic(node)
                val bounds = bounds(matrix, intrinsicWidth, intrinsicHeight)
                frontend += EditorProtocol.NodeSnapshot(
                    node.properties.id, parentId,
                    if (node is RectangleNode && typeIndex[node.properties.id] == "line") EditorProtocol.KIND_LINE else kind(node),
                    bounds.x, bounds.y, bounds.width, bounds.height,
                    Math.toDegrees(atan2(matrix.m01(), matrix.m00())), node.properties.visible, false,
                    propertyIndex[node.properties.id].orEmpty(),
                )
                if (node is GroupNode) visit(node.children, node.properties.id, matrix)
            }
        }
        visit(definition.frontend, "", Matrix4d())
        val backend = definition.backend.map { region ->
            EditorProtocol.NodeSnapshot(region.id, "", EditorProtocol.KIND_REGION, region.x, region.y,
                region.width, region.height, 0.0, true, false,
                propertyIndex[region.id].orEmpty())
        }
        return EditorProtocol.SnapshotPacket(
            revision, menuId, definition.canvas.width, definition.canvas.height,
            dirty, saved, serverVersion, frontend, backend, images, templates,
        )
    }

    fun geometry(id: String, tab: Byte): NodeGeometry {
        if (tab == EditorProtocol.TAB_BACKEND) return definition.backend.firstOrNull { it.id == id }
            ?.let { NodeGeometry(it.id, it.x, it.y, it.width, it.height) }
            ?: throw IllegalArgumentException("后端区域不存在: $id")
        return snapshot("").frontend().firstOrNull { it.id() == id }
            ?.let { NodeGeometry(it.id(), it.x(), it.y(), it.width(), it.height()) }
            ?: throw IllegalArgumentException("前端元素不存在: $id")
    }

    fun hit(point: MenuPoint): String = HitTester(definition.backend).hit(point)?.id.orEmpty()

    private fun commitSource(
        nextSource: String,
        expectedDefinition: MenuDefinition? = null,
        gestureKey: String? = null,
    ) {
        require(nextSource != currentState.source) { "操作没有产生变化" }
        val nextDefinition = parseSaved(nextSource)
        if (expectedDefinition != null) require(nextDefinition == expectedDefinition) { "YAML 修改结果与编辑事务不一致" }
        validate(nextDefinition)
        if (gestureKey == null || gestureKey != activeGestureKey) {
            undo.addLast(currentState)
            while (undo.size > 100) undo.removeFirst()
            redo.clear()
        }
        currentState = DraftState(nextSource, nextDefinition)
        activeGestureKey = gestureKey
        revision++
        saved = !dirty
    }

    private fun requireRevision(expected: Long) {
        require(expected == revision) { "修订冲突：客户端=$expected，服务端=$revision；已发送权威快照" }
    }

    private fun validatePoint(canvas: Canvas, x: Double, y: Double) {
        require(x.isFinite() && y.isFinite()) { "坐标必须为有限数值" }
        require(abs(x) <= canvas.width * 2 && abs(y) <= canvas.height * 2) { "坐标超出编辑安全范围" }
    }

    private fun findVisual(nodes: List<VisualNode>, id: String): VisualNode? {
        nodes.forEach { node ->
            if (node.properties.id == id) return node
            if (node is GroupNode) findVisual(node.children, id)?.let { return it }
        }
        return null
    }

    private fun moveFrontend(nodes: List<VisualNode>, id: String, targetX: Double, targetY: Double): List<VisualNode> {
        var found = false
        fun visit(items: List<VisualNode>, parent: Matrix4dc): List<VisualNode> = items.map { node ->
            if (node.properties.id == id) {
                found = true
                val old = node.properties.transform
                val z = old.offset.z
                val a = parent.m00(); val b = parent.m10(); val c = parent.m01(); val d = parent.m11()
                val rx = targetX - parent.m20() * z - parent.m30()
                val ry = targetY - parent.m21() * z - parent.m31()
                val determinant = a * d - b * c
                require(abs(determinant) > 1e-9) { "父组变换不可逆，无法拖动此元素" }
                val localX = (rx * d - b * ry) / determinant
                val localY = (a * ry - rx * c) / determinant
                node.withProperties(node.properties.copy(transform = old.copy(offset = old.offset.copy(x = localX, y = localY))))
            } else if (node is GroupNode) {
                val matrix = Matrix4d(parent).mul(Transforms.local(node.properties.transform))
                node.copy(children = visit(node.children, matrix))
            } else node
        }
        val result = visit(nodes, Matrix4d())
        require(found) { "前端元素不存在: $id" }
        return result
    }

    private fun resizeFrontend(nodes: List<VisualNode>, id: String, targetWidth: Double, targetHeight: Double): List<VisualNode> {
        var found = false
        fun visit(items: List<VisualNode>, parent: Matrix4dc): List<VisualNode> = items.map { node ->
            if (node.properties.id == id) {
                found = true
                val old = node.properties.transform
                val unitScale = old.copy(scaleX = 1.0, scaleY = 1.0)
                val matrix = Matrix4d(parent).mul(Transforms.local(unitScale))
                val (intrinsicWidth, intrinsicHeight) = intrinsic(node)
                val widthFromX = abs(matrix.m00()) * intrinsicWidth
                val widthFromY = abs(matrix.m10()) * intrinsicHeight
                val heightFromX = abs(matrix.m01()) * intrinsicWidth
                val heightFromY = abs(matrix.m11()) * intrinsicHeight
                val (scaleX, scaleY) = solveLocalScale(
                    targetWidth, targetHeight,
                    widthFromX, widthFromY, heightFromX, heightFromY,
                    old.scaleX, old.scaleY,
                )
                node.withProperties(node.properties.copy(transform = old.copy(scaleX = scaleX, scaleY = scaleY)))
            } else if (node is GroupNode) {
                val matrix = Matrix4d(parent).mul(Transforms.local(node.properties.transform))
                node.copy(children = visit(node.children, matrix))
            } else node
        }
        val result = visit(nodes, Matrix4d())
        require(found) { "前端元素不存在: $id" }
        return result
    }

    private fun solveLocalScale(
        targetWidth: Double,
        targetHeight: Double,
        widthFromX: Double,
        widthFromY: Double,
        heightFromX: Double,
        heightFromY: Double,
        oldScaleX: Double,
        oldScaleY: Double,
    ): Pair<Double, Double> {
        val determinant = widthFromX * heightFromY - widthFromY * heightFromX
        var absoluteX = Double.NaN
        var absoluteY = Double.NaN
        if (abs(determinant) > 1e-9) {
            absoluteX = (targetWidth * heightFromY - widthFromY * targetHeight) / determinant
            absoluteY = (widthFromX * targetHeight - targetWidth * heightFromX) / determinant
        }
        if (!absoluteX.isFinite() || !absoluteY.isFinite() || absoluteX <= 0.0001 || absoluteY <= 0.0001) {
            val currentX = max(0.0001, abs(oldScaleX))
            val currentY = max(0.0001, abs(oldScaleY))
            val currentWidth = widthFromX * currentX + widthFromY * currentY
            val currentHeight = heightFromX * currentX + heightFromY * currentY
            val factor = hypot(targetWidth, targetHeight) / max(0.0001, hypot(currentWidth, currentHeight))
            absoluteX = currentX * factor
            absoluteY = currentY * factor
        }
        val signX = if (oldScaleX < 0) -1.0 else 1.0
        val signY = if (oldScaleY < 0) -1.0 else 1.0
        return signX * absoluteX.coerceIn(0.0001, 10_000.0) to
            signY * absoluteY.coerceIn(0.0001, 10_000.0)
    }

    private fun VisualNode.withProperties(properties: NodeProperties): VisualNode = when (this) {
        is GroupNode -> copy(properties = properties)
        is RectangleNode -> copy(properties = properties)
        is FrameNode -> copy(properties = properties)
        is TextNode -> copy(properties = properties)
        is ImageNode -> copy(properties = properties)
        is ItemNode -> copy(properties = properties)
        is BlockNode -> copy(properties = properties)
    }

    private data class Bounds(val x: Double, val y: Double, val width: Double, val height: Double)

    private fun bounds(matrix: Matrix4dc, width: Double, height: Double): Bounds {
        val corners = listOf(
            Vector3d(-width / 2, -height / 2, 0.0), Vector3d(width / 2, -height / 2, 0.0),
            Vector3d(width / 2, height / 2, 0.0), Vector3d(-width / 2, height / 2, 0.0),
        ).onEach { matrix.transformPosition(it) }
        val minX = corners.minOf { it.x }; val maxX = corners.maxOf { it.x }
        val minY = corners.minOf { it.y }; val maxY = corners.maxOf { it.y }
        val center = Vector3d(); matrix.transformPosition(center)
        return Bounds(center.x, center.y, max(4.0, maxX - minX), max(4.0, maxY - minY))
    }

    private fun intrinsic(node: VisualNode): Pair<Double, Double> = when (node) {
        is GroupNode -> 10.0 to 10.0
        is RectangleNode -> node.width to node.height
        is FrameNode -> node.width to node.height
        is TextNode -> max(12.0, node.content.length * node.size * 0.5) to max(6.0, node.size)
        is ImageNode -> (node.width ?: 32.0) to (node.height ?: 32.0)
        is ItemNode, is BlockNode -> 1.0 to 1.0
    }

    private fun kind(node: VisualNode): Byte = when (node) {
        is GroupNode -> EditorProtocol.KIND_GROUP
        is RectangleNode -> EditorProtocol.KIND_RECTANGLE
        is FrameNode -> EditorProtocol.KIND_FRAME
        is TextNode -> EditorProtocol.KIND_TEXT
        is ImageNode -> EditorProtocol.KIND_IMAGE
        is ItemNode -> EditorProtocol.KIND_ITEM
        is BlockNode -> EditorProtocol.KIND_BLOCK
    }

}

internal data class EditorSaveResult(val changed: Boolean, val fileName: String = "", val backupName: String = "")

/**
 * Structural editor for M3. It rewrites the document only for operations which cannot be
 * expressed as a safe scalar patch (create/delete/template/complex values); every result is
 * immediately reparsed by [EditorDraft] before becoming authoritative.
 */
internal object YamlDocumentEditor {
    data class EditResult(val source: String, val id: String)
    data class MultiEditResult(val source: String, val ids: List<String>)
    private data class Ref(val container: MutableMap<String, Any?>, val id: String, val node: MutableMap<String, Any?>)

    private val loaderOptions = LoaderOptions().apply { isAllowDuplicateKeys = false }
    private val dumpOptions = DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        isPrettyFlow = true
        splitLines = false
        indent = 2
        indicatorIndent = 1
    }

    fun create(source: String, tab: Byte, kind: Byte, parentId: String, initialSource: String = ""): EditResult {
        val root = load(source)
        val ids = allIds(root)
        val base = when (kind) {
            EditorProtocol.KIND_GROUP -> "group"
            EditorProtocol.KIND_RECTANGLE -> "rectangle"
            EditorProtocol.KIND_LINE -> "line"
            EditorProtocol.KIND_FRAME -> "border"
            EditorProtocol.KIND_TEXT -> "text"
            EditorProtocol.KIND_IMAGE -> "image"
            EditorProtocol.KIND_ITEM -> "item"
            EditorProtocol.KIND_BLOCK -> "block"
            EditorProtocol.KIND_REGION -> "region"
            else -> throw IllegalArgumentException("未知元素类型: $kind")
        }
        val id = unique(base, ids)
        if (tab == EditorProtocol.TAB_BACKEND) {
            require(kind == EditorProtocol.KIND_REGION) { "后端编辑器只能创建点击区域" }
            require(parentId.isBlank()) { "点击区域不可被分组" }
            backend(root)[id] = linkedMapOf("x" to 0, "y" to 0, "width" to 80, "height" to 30)
        } else {
            require(tab == EditorProtocol.TAB_FRONTEND) { "未知编辑器 Tab" }
            require(kind != EditorProtocol.KIND_REGION) { "前端编辑器不能创建点击区域" }
            val target = if (parentId.isBlank()) frontend(root) else {
                val parent = findFrontend(root, parentId) ?: throw IllegalArgumentException("父组不存在: $parentId")
                require(parent.node["type"] == "group") { "只能把元素创建在组内" }
                map(parent.node, "children", true)
            }
            target[id] = defaultNode(kind, initialSource)
        }
        return EditResult(dump(root), id)
    }

    fun delete(source: String, tab: Byte, id: String): String {
        val root = load(source)
        val ref = if (tab == EditorProtocol.TAB_FRONTEND) findFrontend(root, id) else findBackend(root, id)
        requireNotNull(ref) { "元素不存在: $id" }
        ref.container.remove(id)
        return dump(root)
    }

    fun deleteMany(source: String, tab: Byte, ids: List<String>): String {
        requireSelection(ids)
        val root = load(source)
        val roots = selectionRoots(root, tab, ids)
        roots.forEach { id ->
            val ref = find(root, tab, id) ?: throw IllegalArgumentException("元素不存在: $id")
            ref.container.remove(id)
        }
        return dump(root)
    }

    fun duplicate(
        source: String,
        tab: Byte,
        selectedIds: List<String>,
        targetParentId: String,
        preserveParents: Boolean,
    ): MultiEditResult {
        requireSelection(selectedIds)
        val root = load(source)
        val roots = selectionRoots(root, tab, selectedIds)
        val allIds = allIds(root).toMutableSet()
        val target = if (preserveParents) null else destination(root, tab, targetParentId)
        val created = mutableListOf<String>()
        roots.forEach { sourceId ->
            val sourceRef = find(root, tab, sourceId) ?: throw IllegalArgumentException("元素不存在: $sourceId")
            val nextId = unique(sourceId, allIds)
            allIds += nextId
            @Suppress("UNCHECKED_CAST")
            val copied = deepCopy(sourceRef.node) as MutableMap<String, Any?>
            if (tab == EditorProtocol.TAB_FRONTEND) renameTemplateChildren(copied, allIds)
            val output = target ?: sourceRef.container
            if (preserveParents) insertAfter(output, sourceId, nextId, copied) else output[nextId] = copied
            created += nextId
        }
        return MultiEditResult(dump(root), created)
    }

    fun group(source: String, selectedIds: List<String>): EditResult {
        requireSelection(selectedIds)
        val root = load(source)
        val roots = selectionRoots(root, EditorProtocol.TAB_FRONTEND, selectedIds)
        val refs = roots.map { findFrontend(root, it) ?: throw IllegalArgumentException("前端元素不存在: $it") }
        val container = refs.first().container
        require(refs.all { it.container === container }) { "只能把同一层级的元素编为一组" }
        val ordered = container.entries.filter { it.key in roots }
        require(ordered.isNotEmpty()) { "没有可编组的元素" }
        val groupId = unique("group", allIds(root))
        val children = linkedMapOf<String, Any?>().apply { ordered.forEach { put(it.key, it.value) } }
        val group = linkedMapOf<String, Any?>("type" to "group", "children" to children)
        val first = ordered.first().key
        val rebuilt = linkedMapOf<String, Any?>()
        container.forEach { (key, value) ->
            if (key == first) rebuilt[groupId] = group
            if (key !in roots) rebuilt[key] = value
        }
        container.clear(); container.putAll(rebuilt)
        return EditResult(dump(root), groupId)
    }

    fun reorder(source: String, tab: Byte, selectedIds: List<String>, beforeId: String): String {
        requireSelection(selectedIds)
        val root = load(source)
        val roots = selectionRoots(root, tab, selectedIds)
        val refs = roots.map { find(root, tab, it) ?: throw IllegalArgumentException("元素不存在: $it") }
        val container = refs.first().container
        require(refs.all { it.container === container }) { "拖动排序只能在同一层级内进行" }
        require(beforeId.isBlank() || beforeId !in roots) { "不能把元素排序到自身之前" }
        if (beforeId.isNotBlank()) {
            val target = find(root, tab, beforeId) ?: throw IllegalArgumentException("排序目标不存在: $beforeId")
            require(target.container === container) { "拖动排序只能在同一层级内进行" }
        }
        val moving = container.entries.filter { it.key in roots }.associateTo(linkedMapOf()) { it.key to it.value }
        val rebuilt = linkedMapOf<String, Any?>()
        var inserted = false
        container.forEach { (key, value) ->
            if (key == beforeId) {
                rebuilt.putAll(moving)
                inserted = true
            }
            if (key !in roots) rebuilt[key] = value
        }
        if (!inserted) rebuilt.putAll(moving)
        require(rebuilt.keys.toList() != container.keys.toList()) { "排序没有产生变化" }
        container.clear(); container.putAll(rebuilt)
        return dump(root)
    }

    fun reparent(source: String, selectedIds: List<String>, targetParentId: String, beforeId: String): String {
        requireSelection(selectedIds)
        val root = load(source)
        val roots = selectionRoots(root, EditorProtocol.TAB_FRONTEND, selectedIds)
        require(beforeId.isBlank() || beforeId !in roots) { "不能把元素移动到自身之前" }
        require(targetParentId.isBlank() || targetParentId !in roots) { "不能把元素移入自身" }

        val parents = parentIndex(root)
        if (targetParentId.isNotBlank()) {
            roots.forEach { rootId ->
                var cursor = targetParentId
                while (cursor.isNotBlank()) {
                    require(cursor != rootId) { "不能把组移入自己的后代" }
                    cursor = parents[cursor].orEmpty()
                }
            }
        }

        val target = targetParentId.takeIf { it.isNotBlank() }?.let { id ->
            findFrontend(root, id) ?: throw IllegalArgumentException("目标组不存在: $id")
        }
        if (target != null) require(target.node["type"] == "group") { "目标父级必须是组" }
        val destination = target?.let { map(it.node, "children", true) } ?: frontend(root)
        if (beforeId.isNotBlank()) {
            val before = findFrontend(root, beforeId)
                ?: throw IllegalArgumentException("插入目标不存在: $beforeId")
            require(before.container === destination) { "插入目标不在目标层级中" }
        }

        val beforeWorld = worldMatrices(root)
        val targetWorld = if (targetParentId.isBlank()) Matrix4d() else beforeWorld[targetParentId]
            ?: throw IllegalArgumentException("目标组不存在: $targetParentId")
        val targetInverse = Matrix4d(targetWorld).also {
            require(abs(it.determinant()) > 1e-9) { "目标层级变换不可逆，无法移动元素" }
        }.invert()

        val moving = roots.map { id ->
            val ref = findFrontend(root, id) ?: throw IllegalArgumentException("前端元素不存在: $id")
            id to ref
        }
        moving.forEach { (id, ref) ->
            require(ref.container.remove(id) != null) { "前端元素不存在: $id" }
        }
        val moved = linkedMapOf<String, Any?>()
        moving.forEach { (id, ref) ->
            val oldWorld = beforeWorld[id] ?: throw IllegalArgumentException("前端元素不存在: $id")
            val local = Matrix4d(targetInverse).mul(oldWorld)
            writeTransform(ref.node, exactTransform(local))
            moved[id] = ref.node
        }

        if (beforeId.isBlank()) {
            destination.putAll(moved)
        } else {
            val rebuilt = linkedMapOf<String, Any?>()
            var inserted = false
            destination.forEach { (id, node) ->
                if (id == beforeId) {
                    rebuilt.putAll(moved)
                    inserted = true
                }
                rebuilt[id] = node
            }
            require(inserted) { "插入目标不存在: $beforeId" }
            destination.clear()
            destination.putAll(rebuilt)
        }

        val afterWorld = worldMatrices(root)
        roots.forEach { id ->
            require(sameMatrix(beforeWorld.getValue(id), afterWorld.getValue(id))) {
                "无法在目标层级中精确保留 $id 的画面变换"
            }
        }
        return dump(root)
    }

    fun setProperty(source: String, tab: Byte, id: String, key: String, value: String): String {
        val root = load(source)
        val ref = if (tab == EditorProtocol.TAB_FRONTEND) findFrontend(root, id) else findBackend(root, id)
        requireNotNull(ref) { "元素不存在: $id" }
        if (key == "id") {
            require(Regex("[a-z0-9][a-z0-9_-]*").matches(value)) { "键名只能使用小写字母、数字、下划线或连字符" }
            require(value == id || value !in allIds(root)) { "元素键名已存在: $value" }
            if (value != id) renameKey(ref.container, id, value)
            return dump(root)
        }
        val property = propertiesForMap(tab, ref.id, ref.node).firstOrNull { it.key() == key }
            ?: throw IllegalArgumentException("$id 不支持属性 $key")
        val parsed = parseValue(property.type(), key, value)
        val segments = key.split('.')
        if (segments.size == 2 && segments[0] in setOf("offset", "rotation", "scale")) {
            val nested = map(ref.node, segments[0], true)
            nested[segments[1]] = parsed
        } else if (parsed === RemoveValue) {
            ref.node.remove(key)
        } else {
            if (key == "source") listOf("path", "image", "material").forEach(ref.node::remove)
            ref.node[key] = parsed
        }
        return dump(root)
    }

    fun instantiate(source: String, template: VisualTemplate): EditResult {
        val root = load(source)
        val templateRoot = load(template.source)
        val sourceFrontend = frontend(templateRoot)
        require(sourceFrontend.size == 1) { "模板必须只有一个根组" }
        val originalRoot = sourceFrontend.entries.single()
        val ids = allIds(root).toMutableSet()
        val rootId = unique(originalRoot.key, ids)
        ids += rootId
        @Suppress("UNCHECKED_CAST")
        val node = deepCopy(originalRoot.value) as? MutableMap<String, Any?>
            ?: throw IllegalArgumentException("模板根节点无效")
        renameTemplateChildren(node, ids)
        frontend(root)[rootId] = node
        return EditResult(dump(root), rootId)
    }

    fun template(source: String, nodeId: String, templateId: String): String {
        require(Regex("[a-z0-9][a-z0-9_-]*").matches(templateId)) { "模板名只能使用小写字母、数字、下划线或连字符" }
        val root = load(source)
        val ref = findFrontend(root, nodeId) ?: throw IllegalArgumentException("组不存在: $nodeId")
        require(ref.node["type"] == "group") { "只有组可以保存为模板" }
        val result = linkedMapOf<String, Any?>(
            "schema-version" to 1,
            "id" to templateId,
            "frontend" to linkedMapOf(nodeId to deepCopy(ref.node)),
        )
        return dump(result)
    }

    fun propertyIndex(source: String): Map<String, List<EditorProtocol.PropertySnapshot>> {
        val root = load(source)
        val result = linkedMapOf<String, List<EditorProtocol.PropertySnapshot>>()
        fun visit(nodes: MutableMap<String, Any?>) {
            nodes.forEach { (id, raw) ->
                @Suppress("UNCHECKED_CAST") val node = raw as? MutableMap<String, Any?> ?: return@forEach
                result[id] = propertiesForMap(EditorProtocol.TAB_FRONTEND, id, node)
                if (node["type"] == "group") visit(map(node, "children", true))
            }
        }
        visit(frontend(root))
        backend(root).forEach { (id, raw) ->
            @Suppress("UNCHECKED_CAST") val node = raw as? MutableMap<String, Any?> ?: return@forEach
            result[id] = propertiesForMap(EditorProtocol.TAB_BACKEND, id, node)
        }
        return result
    }

    fun typeIndex(source: String): Map<String, String> {
        val root = load(source)
        val result = linkedMapOf<String, String>()
        fun visit(nodes: MutableMap<String, Any?>) {
            nodes.forEach { (id, raw) ->
                @Suppress("UNCHECKED_CAST") val node = raw as? MutableMap<String, Any?> ?: return@forEach
                result[id] = node["type"]?.toString().orEmpty()
                if (node["type"] == "group") visit(map(node, "children", true))
            }
        }
        visit(frontend(root))
        return result
    }

    private fun propertiesForMap(tab: Byte, id: String, node: MutableMap<String, Any?>): List<EditorProtocol.PropertySnapshot> = buildList {
        add(prop("id", EditorProtocol.PROPERTY_TEXT, id))
        if (tab == EditorProtocol.TAB_FRONTEND) {
            addVector(node, "offset", 0.0, this)
            addVector(node, "rotation", 0.0, this)
            add(prop("scale.x", EditorProtocol.PROPERTY_NUMBER, nested(node, "scale", "x", 1.0)))
            add(prop("scale.y", EditorProtocol.PROPERTY_NUMBER, nested(node, "scale", "y", 1.0)))
            add(prop("visible", EditorProtocol.PROPERTY_BOOLEAN, node["visible"] ?: true))
            when (node["type"]?.toString()) {
                "rectangle" -> direct(node, this, "width", number = true, "height", "color", "opacity")
                "line" -> direct(node, this, "width", number = true, "thickness", "color", "opacity")
                "frame" -> direct(node, this, "width", number = true, "height", "thickness", "color", "opacity")
                "text" -> {
                    add(prop("content", EditorProtocol.PROPERTY_MULTILINE, node["content"] ?: ""))
                    add(prop("size", EditorProtocol.PROPERTY_NUMBER, node["size"] ?: 10))
                    add(prop("font", EditorProtocol.PROPERTY_TEXT, node["font"] ?: "minecraft:default"))
                    add(prop("opacity", EditorProtocol.PROPERTY_INTEGER, node["opacity"] ?: 255))
                    add(prop("line-width", EditorProtocol.PROPERTY_INTEGER, node["line-width"] ?: 200))
                    add(EditorProtocol.PropertySnapshot("alignment", EditorProtocol.PROPERTY_CHOICE,
                        node["alignment"]?.toString() ?: "center", listOf("left", "center", "right")))
                    add(prop("update", EditorProtocol.PROPERTY_INTEGER, node["update"] ?: -1))
                }
                "image" -> {
                    add(prop("source", EditorProtocol.PROPERTY_TEXT, node["source"] ?: node["path"] ?: node["image"] ?: node["material"] ?: ""))
                    add(prop("width", EditorProtocol.PROPERTY_NUMBER, node["width"] ?: ""))
                    add(prop("height", EditorProtocol.PROPERTY_NUMBER, node["height"] ?: ""))
                    add(prop("opacity", EditorProtocol.PROPERTY_INTEGER, node["opacity"] ?: 255))
                    add(prop("update", EditorProtocol.PROPERTY_INTEGER, node["update"] ?: -1))
                }
                "item" -> {
                    add(prop("material", EditorProtocol.PROPERTY_TEXT, node["material"] ?: "minecraft:diamond_sword"))
                    add(EditorProtocol.PropertySnapshot("context", EditorProtocol.PROPERTY_CHOICE,
                        node["context"]?.toString() ?: "GUI",
                        listOf("GUI", "HEAD", "FIXED", "GROUND", "NONE", "FIRSTPERSON_LEFTHAND", "FIRSTPERSON_RIGHTHAND", "THIRDPERSON_LEFTHAND", "THIRDPERSON_RIGHTHAND")))
                }
                "block" -> add(prop("block-data", EditorProtocol.PROPERTY_TEXT, node["block-data"] ?: "minecraft:stone"))
            }
        } else {
            add(prop("x", EditorProtocol.PROPERTY_NUMBER, node["x"] ?: 0))
            add(prop("y", EditorProtocol.PROPERTY_NUMBER, node["y"] ?: 0))
            add(prop("width", EditorProtocol.PROPERTY_NUMBER, node["width"] ?: 1))
            add(prop("height", EditorProtocol.PROPERTY_NUMBER, node["height"] ?: 1))
            add(prop("priority", EditorProtocol.PROPERTY_INTEGER, node["priority"] ?: 0))
            add(prop("tooltip", EditorProtocol.PROPERTY_MULTILINE, (node["tooltip"] as? List<*>)?.joinToString("\n") ?: node["tooltip"] ?: ""))
            add(prop("update", EditorProtocol.PROPERTY_INTEGER, node["update"] ?: -1))
            add(prop("condition", EditorProtocol.PROPERTY_TEXT, node["condition"] ?: ""))
            add(prop("actions", EditorProtocol.PROPERTY_MULTILINE, yamlValue(node["actions"])))
            add(prop("deny", EditorProtocol.PROPERTY_MULTILINE, yamlValue(node["deny"])))
        }
    }

    private fun addVector(node: MutableMap<String, Any?>, key: String, fallback: Double, target: MutableList<EditorProtocol.PropertySnapshot>) {
        target += prop("$key.x", EditorProtocol.PROPERTY_NUMBER, nested(node, key, "x", fallback))
        target += prop("$key.y", EditorProtocol.PROPERTY_NUMBER, nested(node, key, "y", fallback))
        target += prop("$key.z", EditorProtocol.PROPERTY_NUMBER, nested(node, key, "z", fallback))
    }

    private fun direct(node: MutableMap<String, Any?>, target: MutableList<EditorProtocol.PropertySnapshot>, first: String, number: Boolean, vararg rest: String) {
        (listOf(first) + rest).forEach { key ->
            val type = when (key) {
                "color" -> EditorProtocol.PROPERTY_COLOR
                "opacity" -> EditorProtocol.PROPERTY_INTEGER
                else -> if (number) EditorProtocol.PROPERTY_NUMBER else EditorProtocol.PROPERTY_TEXT
            }
            val fallback: Any = when (key) { "color" -> "#FFFFFF"; "opacity" -> 255; else -> 1 }
            target += prop(key, type, node[key] ?: fallback)
        }
    }

    private fun prop(key: String, type: Byte, value: Any?): EditorProtocol.PropertySnapshot =
        EditorProtocol.PropertySnapshot(key, type, when (value) {
            null -> ""
            is Double -> number(value)
            is Float -> number(value.toDouble())
            else -> value.toString()
        })

    private fun parseValue(type: Byte, key: String, value: String): Any? {
        if (key in setOf("width", "height") && value.isBlank()) return RemoveValue
        return when (type) {
            EditorProtocol.PROPERTY_NUMBER -> value.toDoubleOrNull()?.also { require(it.isFinite()) { "$key 必须为有限数值" } }
                ?: throw IllegalArgumentException("$key 必须为数值")
            EditorProtocol.PROPERTY_INTEGER -> value.toIntOrNull() ?: throw IllegalArgumentException("$key 必须为整数")
            EditorProtocol.PROPERTY_BOOLEAN -> when (value.lowercase()) {
                "true" -> true; "false" -> false; else -> throw IllegalArgumentException("$key 必须为 true 或 false")
            }
            EditorProtocol.PROPERTY_COLOR -> value.also { require(Regex("#[0-9a-fA-F]{6}").matches(it)) { "$key 必须为 #RRGGBB；透明度请修改 opacity" } }
            EditorProtocol.PROPERTY_MULTILINE -> when (key) {
                "tooltip" -> value.lines().filter { it.isNotEmpty() }
                "actions", "deny" -> if (value.isBlank()) RemoveValue else Yaml(SafeConstructor(loaderOptions)).load<Any?>(value)
                else -> value
            }
            else -> if (value.isBlank() && key in setOf("condition")) RemoveValue else value
        }
    }

    private fun defaultNode(kind: Byte, initialSource: String): MutableMap<String, Any?> = when (kind) {
        EditorProtocol.KIND_GROUP -> linkedMapOf("type" to "group", "children" to linkedMapOf<String, Any?>())
        EditorProtocol.KIND_RECTANGLE -> linkedMapOf("type" to "rectangle", "width" to 100, "height" to 50, "color" to "#FFFFFF")
        EditorProtocol.KIND_LINE -> linkedMapOf("type" to "line", "width" to 100, "thickness" to 1, "color" to "#FFFFFF")
        EditorProtocol.KIND_FRAME -> linkedMapOf("type" to "frame", "width" to 100, "height" to 50, "thickness" to 1, "color" to "#FFFFFF")
        EditorProtocol.KIND_TEXT -> linkedMapOf("type" to "text", "content" to "&fText", "size" to 10)
        EditorProtocol.KIND_IMAGE -> linkedMapOf("type" to "image", "source" to (initialSource.ifBlank { "/example.png" }), "width" to 32, "height" to 32)
        EditorProtocol.KIND_ITEM -> linkedMapOf("type" to "item", "material" to "minecraft:diamond_sword", "context" to "GUI", "scale" to linkedMapOf("x" to 32, "y" to 32))
        EditorProtocol.KIND_BLOCK -> linkedMapOf("type" to "block", "block-data" to "minecraft:stone", "scale" to linkedMapOf("x" to 32, "y" to 32))
        else -> throw IllegalArgumentException("未知前端元素类型")
    }

    private fun renameTemplateChildren(node: MutableMap<String, Any?>, ids: MutableSet<String>) {
        if (node["type"] != "group") return
        val children = map(node, "children", true)
        val renamed = linkedMapOf<String, Any?>()
        children.forEach { (originalId, childValue) ->
            val id = unique(originalId, ids)
            ids += id
            @Suppress("UNCHECKED_CAST")
            val child = deepCopy(childValue) as MutableMap<String, Any?>
            renameTemplateChildren(child, ids)
            renamed[id] = child
        }
        children.clear(); children.putAll(renamed)
    }

    private fun destination(root: MutableMap<String, Any?>, tab: Byte, parentId: String): MutableMap<String, Any?> {
        if (tab == EditorProtocol.TAB_BACKEND) {
            require(parentId.isBlank()) { "点击区域不可被分组" }
            return backend(root)
        }
        require(tab == EditorProtocol.TAB_FRONTEND) { "未知编辑器 Tab" }
        if (parentId.isBlank()) return frontend(root)
        val parent = findFrontend(root, parentId) ?: throw IllegalArgumentException("目标组不存在: $parentId")
        require(parent.node["type"] == "group") { "复制目标必须是组" }
        return map(parent.node, "children", true)
    }

    private fun find(root: MutableMap<String, Any?>, tab: Byte, id: String): Ref? = when (tab) {
        EditorProtocol.TAB_FRONTEND -> findFrontend(root, id)
        EditorProtocol.TAB_BACKEND -> findBackend(root, id)
        else -> throw IllegalArgumentException("未知编辑器 Tab")
    }

    private fun selectionRoots(root: MutableMap<String, Any?>, tab: Byte, ids: List<String>): List<String> {
        val selected = ids.distinct().toSet()
        require(selected.size == ids.distinct().size) { "选择中包含重复元素" }
        selected.forEach { requireNotNull(find(root, tab, it)) { "元素不存在: $it" } }
        if (tab == EditorProtocol.TAB_BACKEND) return ids.distinct()
        val parents = parentIndex(root)
        return ids.distinct().filter { id ->
            var parent = parents[id].orEmpty()
            var nested = false
            while (parent.isNotBlank()) {
                if (parent in selected) { nested = true; break }
                parent = parents[parent].orEmpty()
            }
            !nested
        }
    }

    private fun parentIndex(root: MutableMap<String, Any?>): Map<String, String> = buildMap {
        fun visit(nodes: MutableMap<String, Any?>, parent: String) {
            nodes.forEach { (id, raw) ->
                put(id, parent)
                @Suppress("UNCHECKED_CAST") val node = raw as? MutableMap<String, Any?> ?: return@forEach
                if (node["type"] == "group") visit(map(node, "children", true), id)
            }
        }
        visit(frontend(root), "")
    }

    private fun worldMatrices(root: MutableMap<String, Any?>): Map<String, Matrix4d> = buildMap {
        fun visit(nodes: MutableMap<String, Any?>, parent: Matrix4dc) {
            nodes.forEach { (id, raw) ->
                @Suppress("UNCHECKED_CAST") val node = raw as? MutableMap<String, Any?> ?: return@forEach
                val world = Matrix4d(parent).mul(Transforms.local(yamlTransform(node)))
                put(id, world)
                if (node["type"] == "group") visit(map(node, "children", true), world)
            }
        }
        visit(frontend(root), Matrix4d())
    }

    private fun yamlTransform(node: MutableMap<String, Any?>): Transform = Transform(
        offset = Vec3(
            numeric(nested(node, "offset", "x", 0.0), "offset.x"),
            numeric(nested(node, "offset", "y", 0.0), "offset.y"),
            numeric(nested(node, "offset", "z", 0.0), "offset.z"),
        ),
        rotation = Vec3(
            numeric(nested(node, "rotation", "x", 0.0), "rotation.x"),
            numeric(nested(node, "rotation", "y", 0.0), "rotation.y"),
            numeric(nested(node, "rotation", "z", 0.0), "rotation.z"),
        ),
        scaleX = numeric(nested(node, "scale", "x", 1.0), "scale.x"),
        scaleY = numeric(nested(node, "scale", "y", 1.0), "scale.y"),
    )

    private fun numeric(value: Any?, path: String): Double {
        val result = when (value) {
            is Number -> value.toDouble()
            else -> value?.toString()?.toDoubleOrNull()
        }
        require(result != null && result.isFinite()) { "$path 必须为有限数值" }
        return result
    }

    private fun exactTransform(matrix: Matrix4dc): Transform {
        require(abs(matrix.m03()) < 1e-8 && abs(matrix.m13()) < 1e-8 &&
                abs(matrix.m23()) < 1e-8 && abs(matrix.m33() - 1.0) < 1e-8) {
            "目标组产生了不可表示的透视变换"
        }
        val xAxis = Vector3d(matrix.m00(), matrix.m01(), matrix.m02())
        val yAxis = Vector3d(matrix.m10(), matrix.m11(), matrix.m12())
        val zAxis = Vector3d(matrix.m20(), matrix.m21(), matrix.m22())
        var scaleX = xAxis.length()
        val scaleY = yAxis.length()
        val scaleZ = zAxis.length()
        require(scaleX > 1e-9 && scaleY > 1e-9 && scaleZ > 1e-9) { "目标组变换不可逆，无法移入元素" }
        xAxis.div(scaleX); yAxis.div(scaleY); zAxis.div(scaleZ)
        require(abs(xAxis.dot(yAxis)) < 1e-7 && abs(xAxis.dot(zAxis)) < 1e-7 && abs(yAxis.dot(zAxis)) < 1e-7) {
            "目标组与元素的非等比缩放会产生斜切，无法精确保留画面变换"
        }
        val handedness = Vector3d(xAxis).cross(yAxis).dot(zAxis)
        require(abs(abs(handedness) - 1.0) < 1e-7 && abs(scaleZ - 1.0) < 1e-7) {
            "目标组变换超出菜单 YAML 可表示范围"
        }
        if (handedness < 0.0) {
            xAxis.negate()
            scaleX = -scaleX
        }
        val basis = Matrix3d().setColumn(0, xAxis).setColumn(1, yAxis).setColumn(2, zAxis)
        val angles = Quaterniond().setFromNormalized(basis).normalize().getEulerAnglesZXY(Vector3d())
        val result = Transform(
            offset = Vec3(matrix.m30(), matrix.m31(), matrix.m32()),
            rotation = Vec3(Math.toDegrees(angles.x), Math.toDegrees(angles.y), Math.toDegrees(angles.z)),
            scaleX = scaleX,
            scaleY = scaleY,
        )
        require(sameMatrix(matrix, Transforms.local(result))) { "无法把画面变换转换为菜单 YAML 参数" }
        return result
    }

    private fun writeTransform(node: MutableMap<String, Any?>, transform: Transform) {
        node["offset"] = linkedMapOf(
            "x" to scalar(transform.offset.x), "y" to scalar(transform.offset.y), "z" to scalar(transform.offset.z),
        )
        node["rotation"] = linkedMapOf(
            "x" to scalar(transform.rotation.x), "y" to scalar(transform.rotation.y), "z" to scalar(transform.rotation.z),
        )
        node["scale"] = linkedMapOf("x" to scalar(transform.scaleX), "y" to scalar(transform.scaleY))
    }

    private fun scalar(value: Double): Number =
        if (abs(value - Math.rint(value)) < 1e-9) Math.round(value) else value

    private fun sameMatrix(first: Matrix4dc, second: Matrix4dc, tolerance: Double = 1e-6): Boolean =
        listOf(
            first.m00() - second.m00(), first.m01() - second.m01(), first.m02() - second.m02(), first.m03() - second.m03(),
            first.m10() - second.m10(), first.m11() - second.m11(), first.m12() - second.m12(), first.m13() - second.m13(),
            first.m20() - second.m20(), first.m21() - second.m21(), first.m22() - second.m22(), first.m23() - second.m23(),
            first.m30() - second.m30(), first.m31() - second.m31(), first.m32() - second.m32(), first.m33() - second.m33(),
        ).all { abs(it) <= tolerance }

    private fun requireSelection(ids: List<String>) {
        require(ids.isNotEmpty()) { "没有选择元素" }
        require(ids.size <= 1_000) { "一次最多操作 1000 个元素" }
        require(ids.none { it.isBlank() }) { "元素键名不能为空" }
    }

    private fun insertAfter(
        container: MutableMap<String, Any?>,
        afterId: String,
        id: String,
        value: MutableMap<String, Any?>,
    ) {
        val rebuilt = linkedMapOf<String, Any?>()
        var inserted = false
        container.forEach { (key, existing) ->
            rebuilt[key] = existing
            if (key == afterId) { rebuilt[id] = value; inserted = true }
        }
        if (!inserted) rebuilt[id] = value
        container.clear(); container.putAll(rebuilt)
    }

    private fun allIds(root: MutableMap<String, Any?>): Set<String> = buildSet {
        fun visit(nodes: MutableMap<String, Any?>) {
            nodes.forEach { (id, raw) ->
                add(id)
                @Suppress("UNCHECKED_CAST") val node = raw as? MutableMap<String, Any?> ?: return@forEach
                if (node["type"] == "group") visit(map(node, "children", true))
            }
        }
        visit(frontend(root)); addAll(backend(root).keys)
    }

    private fun findFrontend(root: MutableMap<String, Any?>, id: String): Ref? {
        fun visit(nodes: MutableMap<String, Any?>): Ref? {
            nodes.forEach { (key, raw) ->
                @Suppress("UNCHECKED_CAST") val node = raw as? MutableMap<String, Any?> ?: return@forEach
                if (key == id) return Ref(nodes, key, node)
                if (node["type"] == "group") visit(map(node, "children", true))?.let { return it }
            }
            return null
        }
        return visit(frontend(root))
    }

    private fun findBackend(root: MutableMap<String, Any?>, id: String): Ref? {
        val nodes = backend(root)
        @Suppress("UNCHECKED_CAST") val node = nodes[id] as? MutableMap<String, Any?> ?: return null
        return Ref(nodes, id, node)
    }

    private fun frontend(root: MutableMap<String, Any?>): MutableMap<String, Any?> = map(root, "frontend", true)
    private fun backend(root: MutableMap<String, Any?>): MutableMap<String, Any?> = map(root, "backend", true)

    @Suppress("UNCHECKED_CAST")
    private fun map(parent: MutableMap<String, Any?>, key: String, create: Boolean): MutableMap<String, Any?> {
        val existing = parent[key]
        if (existing is MutableMap<*, *>) return existing as MutableMap<String, Any?>
        require(existing == null && create) { "$key 必须为 YAML 对象" }
        return linkedMapOf<String, Any?>().also { parent[key] = it }
    }

    private fun nested(node: MutableMap<String, Any?>, group: String, key: String, fallback: Any): Any {
        @Suppress("UNCHECKED_CAST") val values = node[group] as? Map<String, Any?>
        return values?.get(key) ?: fallback
    }

    private fun renameKey(map: MutableMap<String, Any?>, old: String, next: String) {
        val copy = linkedMapOf<String, Any?>()
        map.forEach { (key, value) -> copy[if (key == old) next else key] = value }
        map.clear(); map.putAll(copy)
    }

    private fun unique(base: String, ids: Set<String>): String {
        if (base !in ids) return base
        var suffix = 2
        while ("$base$suffix" in ids) suffix++
        return "$base$suffix"
    }

    @Suppress("UNCHECKED_CAST")
    private fun load(source: String): MutableMap<String, Any?> {
        val raw = Yaml(SafeConstructor(loaderOptions)).load<Any?>(source) as? Map<*, *>
            ?: throw IllegalArgumentException("菜单 YAML 根必须为对象")
        return deepCopy(raw) as MutableMap<String, Any?>
    }

    private fun dump(root: Map<String, Any?>): String = Yaml(dumpOptions).dump(root)

    private fun deepCopy(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associateTo(linkedMapOf()) { it.key.toString() to deepCopy(it.value) }
        is List<*> -> value.map(::deepCopy).toMutableList()
        else -> value
    }

    private fun yamlValue(value: Any?): String {
        if (value == null) return ""
        return Yaml(dumpOptions).dump(value).trimEnd()
    }

    private fun number(value: Double): String = if (abs(value - Math.rint(value)) < 1e-9) Math.round(value).toString()
        else String.format(Locale.ROOT, "%.8f", value).trimEnd('0').trimEnd('.')

    private object RemoveValue
}

/** Scalar-only patching deliberately preserves all untouched YAML text, comments, anchors and business fields. */
internal object YamlScalarPatcher {
    private val decimal = DecimalFormat("0.########", DecimalFormatSymbols(Locale.ROOT)).apply { isGroupingUsed = false }

    fun frontendOffset(source: String, id: String, x: Double, y: Double): String =
        frontendVector(source, id, "offset", x, y)

    fun frontendScale(source: String, id: String, x: Double, y: Double): String =
        frontendVector(source, id, "scale", x, y)

    private fun frontendVector(source: String, id: String, key: String, x: Double, y: Double): String {
        val doc = Lines(source)
        val block = doc.block(id)
        val childIndent = doc.childIndent(block)
        val vectorIndex = doc.directProperty(block, key)
        if (vectorIndex == null) {
            doc.lines.add(block.start + 1, " ".repeat(childIndent) + "$key: {x: ${number(x)}, y: ${number(y)}}")
            return doc.join()
        }
        val line = doc.lines[vectorIndex]
        val value = line.substringAfter(':').substringBefore('#').trim()
        when {
            value.startsWith("{") && value.endsWith("}") -> {
                var body = value.removePrefix("{").removeSuffix("}")
                body = setInline(body, "x", number(x))
                body = setInline(body, "y", number(y))
                val prefix = line.substringBefore(':') + ": "
                val comment = line.substringAfter('#', "").let { if (it.isEmpty()) "" else " #$it" }
                doc.lines[vectorIndex] = "$prefix{$body}$comment"
            }
            value.isEmpty() -> {
                val vectorBlock = doc.blockAt(vectorIndex, doc.indent(line))
                setDirectScalar(doc, vectorBlock, "x", number(x))
                setDirectScalar(doc, doc.blockAt(vectorIndex, doc.indent(doc.lines[vectorIndex])), "y", number(y))
            }
            else -> throw IllegalArgumentException("$id.$key 使用别名或复杂结构，M3 为避免破坏 YAML 已设为只读")
        }
        return doc.join()
    }

    fun backendRegion(source: String, region: InteractionRegion): String {
        val doc = Lines(source)
        listOf("x" to number(region.x), "y" to number(region.y), "width" to number(region.width), "height" to number(region.height)).forEach { (key, value) ->
            setDirectScalar(doc, doc.block(region.id), key, value)
        }
        return doc.join()
    }

    private fun setDirectScalar(doc: Lines, block: Block, key: String, value: String) {
        val found = doc.directProperty(block, key)
        if (found == null) {
            doc.lines.add(block.start + 1, " ".repeat(doc.childIndent(block)) + "$key: $value")
            return
        }
        val line = doc.lines[found]
        val rawValue = line.substringAfter(':').substringBefore('#').trim()
        if (rawValue.startsWith("{") || rawValue.startsWith("[") || rawValue.startsWith("*") || rawValue.startsWith("&")) {
            throw IllegalArgumentException("${block.key}.$key 使用复杂 YAML 结构，M3 为避免破坏文档已拒绝保存")
        }
        val comment = line.substringAfter('#', "").let { if (it.isEmpty()) "" else " #$it" }
        doc.lines[found] = line.substringBefore(':') + ": $value$comment"
    }

    private fun setInline(body: String, key: String, value: String): String {
        val matcher = Regex("(^|,)\\s*${Regex.escape(key)}\\s*:\\s*([^,}]*)")
        val match = matcher.find(body)
        return if (match != null) {
            val replacement = match.groupValues[1] + (if (match.groupValues[1].isEmpty()) "" else " ") + "$key: $value"
            body.replaceRange(match.range, replacement)
        } else body.trim().let { if (it.isEmpty()) "$key: $value" else "$it, $key: $value" }
    }

    private fun number(value: Double): String = decimal.format(if (abs(value) < 0.000000005) 0.0 else value)

    private data class Block(val key: String, val start: Int, val end: Int, val indent: Int)

    private class Lines(source: String) {
        val newline = if (source.contains("\r\n")) "\r\n" else "\n"
        val trailing = source.endsWith("\n")
        val lines = source.split(Regex("\\r?\\n")).toMutableList().also { if (trailing && it.lastOrNull() == "") it.removeLast() }

        fun join(): String = lines.joinToString(newline) + if (trailing) newline else ""
        fun indent(line: String): Int = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) line.length else it }

        fun block(key: String): Block {
            val candidates = lines.indices.filter { parseBlockKey(lines[it]) == key }
            require(candidates.size == 1) { if (candidates.isEmpty()) "YAML 中找不到元素 $key" else "YAML 中元素 $key 不唯一" }
            return blockAt(candidates.single(), indent(lines[candidates.single()]))
        }

        fun blockAt(start: Int, level: Int): Block {
            var end = lines.size
            for (index in start + 1 until lines.size) {
                val trimmed = lines[index].trim()
                if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
                if (indent(lines[index]) <= level) { end = index; break }
            }
            return Block(parseBlockKey(lines[start]).orEmpty(), start, end, level)
        }

        fun childIndent(block: Block): Int = (block.start + 1 until block.end)
            .filter { lines[it].trim().isNotEmpty() && !lines[it].trimStart().startsWith('#') && indent(lines[it]) > block.indent }
            .minOfOrNull { indent(lines[it]) } ?: (block.indent + 2)

        fun directProperty(block: Block, key: String): Int? {
            val level = childIndent(block)
            return (block.start + 1 until block.end).firstOrNull { index ->
                indent(lines[index]) == level && parseAnyKey(lines[index]) == key
            }
        }

        private fun parseBlockKey(line: String): String? {
            val trimmed = line.trim()
            if (trimmed.startsWith('#') || !trimmed.endsWith(':')) return null
            return unquote(trimmed.dropLast(1).trim())
        }

        private fun parseAnyKey(line: String): String? {
            val trimmed = line.trimStart()
            if (trimmed.startsWith('#')) return null
            val colon = trimmed.indexOf(':')
            if (colon <= 0) return null
            return unquote(trimmed.substring(0, colon).trim())
        }

        private fun unquote(value: String): String = when {
            value.length >= 2 && value.first() == '\'' && value.last() == '\'' -> value.substring(1, value.length - 1).replace("''", "'")
            value.length >= 2 && value.first() == '"' && value.last() == '"' -> value.substring(1, value.length - 1)
            else -> value
        }
    }
}
