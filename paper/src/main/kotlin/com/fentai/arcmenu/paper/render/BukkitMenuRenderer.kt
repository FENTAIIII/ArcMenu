package com.fentai.arcmenu.paper.render

import com.fentai.arcmenu.core.animation.AnimationSnapshot
import com.fentai.arcmenu.core.geometry.MenuPlane
import com.fentai.arcmenu.core.model.*
import com.fentai.arcmenu.core.render.*
import com.fentai.arcmenu.paper.resource.CraftEngineBridge
import com.fentai.arcmenu.paper.resource.ImageAsset
import com.fentai.arcmenu.paper.resource.NineSliceAsset
import com.fentai.arcmenu.paper.resource.NineSlicePart
import com.fentai.arcmenu.paper.resource.ResourcePackService
import com.fentai.arcmenu.paper.performance.PerformanceMetrics
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Matrix4d
import org.joml.Matrix4dc
import org.joml.Matrix4f
import org.joml.Quaternionf

enum class MenuViewMode { FRONTEND_PREVIEW, BACKEND_PREVIEW, RUNTIME }

/** Private non-persistent Display renderer shared by read-only previews and running menus. */
class BukkitMenuRenderer(
    private val plugin: Plugin,
    private val resources: ResourcePackService,
    private val craftEngine: CraftEngineBridge,
    private val performance: PerformanceMetrics,
) {
    private val compiler = SceneCompiler()
    private data class ImageHandle(val display: TextDisplay, var primitive: Image)
    private data class TooltipSkinHandle(
        val asset: NineSliceAsset,
        val displays: Map<NineSlicePart, TextDisplay>,
        var visible: Boolean = false,
    )

    fun hasCursorAsset(): Boolean =
        resources.resolve(CURSOR_IMAGE_SOURCE) != null && resources.resolve(CURSOR_CHOOSE_IMAGE_SOURCE) != null

    fun validate(document: MenuDocument) {
        compiler.compile(document.definition.frontend).forEach { primitive ->
            require(Matrix4f(primitive.transform).isFinite) { "${primitive.id}: 组合变换超出展示实体数值范围" }
            when (primitive) {
                is Image -> if ('%' !in primitive.node.source) requireNotNull(resources.resolve(primitive.node.source)) {
                    "${primitive.id}: images 中不存在 ${primitive.node.source}；新增图片后请使用 /arcmenu reload all"
                }
                is Item -> require(itemMaterial(primitive.node.material) != null || craftEngine.hasItem(primitive.node.material)) {
                    "${primitive.id}: 原版及 CraftEngine 中均不存在物品 ${primitive.node.material}"
                }
                is Block -> try { Bukkit.createBlockData(primitive.node.blockData) } catch (error: IllegalArgumentException) {
                    throw IllegalArgumentException("${primitive.id}: 无效方块状态 ${primitive.node.blockData}", error)
                }
                else -> Unit
            }
        }
    }

    fun create(
        player: Player,
        menu: MenuDefinition,
        plane: MenuPlane,
        mode: MenuViewMode,
        resolve: (String) -> String = { it },
        tooltipStyle: TooltipStyle? = null,
        cursorStyle: CursorStyle? = null,
        initialAnimation: AnimationSnapshot = AnimationSnapshot(),
    ): MenuView {
        check(Bukkit.isPrimaryThread()) { "Display operations require the server thread" }
        val view = MenuView(player, plane)
        try {
            compiler.compile(menu.frontend, initialAnimation).forEach { view.draw(it, resolve, initialAnimation) }
            if (mode == MenuViewMode.BACKEND_PREVIEW) menu.backend.forEach { view.region(it) }
            if (mode == MenuViewMode.RUNTIME && tooltipStyle != null) view.enableTooltip(tooltipStyle)
            if (mode == MenuViewMode.RUNTIME) view.configureCursor(cursorStyle)
            view.show()
            return view
        } catch (error: Exception) {
            view.close()
            throw error
        }
    }

    inner class MenuView(private val player: Player, private val plane: MenuPlane) : AutoCloseable {
        private val entities = mutableListOf<Display>()
        private val overlays = mutableMapOf<String, TextDisplay>()
        private val texts = mutableMapOf<String, TextDisplay>()
        private val textValues = mutableMapOf<String, String>()
        private val textFonts = mutableMapOf<String, String>()
        private val textOpacities = mutableMapOf<String, Int>()
        private val animatedContentIds = mutableSetOf<String>()
        private val animatedOpacityIds = mutableSetOf<String>()
        private val displayMatrices = mutableMapOf<Int, Matrix4f>()
        private val displayRotations = mutableMapOf<Int, Quaternionf>()
        private val interpolationPrimed = mutableSetOf<Int>()
        private val sceneDisplays = mutableMapOf<String, Display>()
        private val images = mutableMapOf<String, ImageHandle>()
        private val imageValues = mutableMapOf<String, String>()
        private var tooltip: TextDisplay? = null
        private var tooltipStyle: TooltipStyle? = null
        private var tooltipSkin: TooltipSkinHandle? = null
        private var cursor: TextDisplay? = null
        private var cursorAsset: ImageAsset? = null
        private var cursorDefaultAsset: ImageAsset? = null
        private var cursorChooseAsset: ImageAsset? = null
        private var tooltipLines: List<String> = emptyList()
        private var tooltipPoint: MenuPoint? = null
        private var selected: String? = null
        private var marker: Pair<TextDisplay, TextDisplay>? = null
        private var shown = false
        private val anchor = Location(player.world, plane.origin.x, plane.origin.y, plane.origin.z)
        // Entities are positioned at the anchor; their matrices must not add that world translation twice.
        private val base = Matrix4d().translate(-plane.origin.x, -plane.origin.y, -plane.origin.z).mul(plane.matrix())
        val entityCount: Int get() = entities.size
        val valid: Boolean get() = entities.all { it.isValid }

        fun draw(
            primitive: Primitive,
            resolve: (String) -> String = { it },
            animation: AnimationSnapshot = AnimationSnapshot(),
        ) {
            when (primitive) {
                is Quad -> quad(primitive.transform, primitive.width, primitive.height, primitive.argb).also {
                    sceneDisplays[primitive.id] = it
                }
                is Text -> {
                    val node = primitive.node
                    val display = spawn(TextDisplay::class.java)
                    val content = resolve(animation.textContents[primitive.id] ?: node.content)
                    display.text(textComponent(content, node.font))
                    display.backgroundColor = Color.fromARGB(0)
                    display.isDefaultBackground = false
                    val opacity = animation.textOpacities[primitive.id] ?: node.opacity
                    display.textOpacity = opacity.toByte()
                    display.lineWidth = node.lineWidth
                    display.alignment = TextDisplay.TextAlignment.valueOf(node.alignment.uppercase())
                    display.isShadowed = false
                    // A native default-font line is 9 pixels at 0.025 world units/pixel.
                    matrix(display, textMatrix(primitive))
                    sceneDisplays[primitive.id] = display
                    texts[primitive.id] = display
                    textValues[primitive.id] = content
                    textFonts[primitive.id] = node.font
                    textOpacities[primitive.id] = opacity
                    if (primitive.id in animation.textContents) animatedContentIds += primitive.id
                    if (primitive.id in animation.textOpacities) animatedOpacityIds += primitive.id
                }
                is Image -> {
                    val source = resolve(primitive.node.source)
                    val asset = runCatching { resources.resolve(source) }.getOrNull()
                        ?: throw IllegalArgumentException("${primitive.id}: 当前玩家解析后的图片不存在或路径无效: $source")
                    val display = spawn(TextDisplay::class.java)
                    configureImage(display, primitive, asset)
                    sceneDisplays[primitive.id] = display
                    images[primitive.id] = ImageHandle(display, primitive)
                    imageValues[primitive.id] = source
                }
                is Item -> {
                    val display = spawn(ItemDisplay::class.java)
                    val item = itemMaterial(primitive.node.material)?.let(::ItemStack)
                        ?: craftEngine.buildItem(primitive.node.material, player)
                        ?: throw IllegalArgumentException("${primitive.id}: 无法构建物品 ${primitive.node.material}")
                    display.setItemStack(item)
                    display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.valueOf(primitive.node.context)
                    // Item/block models use logical units too: authors choose their scale explicitly.
                    matrix(display, primitive.transform)
                    sceneDisplays[primitive.id] = display
                }
                is Block -> {
                    val display = spawn(BlockDisplay::class.java)
                    display.block = Bukkit.createBlockData(primitive.node.blockData)
                    matrix(display, Matrix4d(primitive.transform).translate(-0.5, -0.5, -0.5))
                    sceneDisplays[primitive.id] = display
                }
            }
        }

        /** Applies a timeline snapshot to existing entities; no entity is spawned or removed here. */
        fun updateAnimation(
            menu: MenuDefinition,
            animation: AnimationSnapshot,
            resolve: (String) -> String = { it },
        ) {
            compiler.compile(menu.frontend, animation).forEach { primitive ->
                val display = sceneDisplays[primitive.id] ?: return@forEach
                when (primitive) {
                    is Quad -> matrix(display, quadMatrix(primitive.transform, primitive.width, primitive.height), 1)
                    is Text -> matrix(display, textMatrix(primitive), 1)
                    is Image -> {
                        val source = imageValues[primitive.id] ?: resolve(primitive.node.source)
                        val asset = runCatching { resources.resolve(source) }.getOrNull() ?: return@forEach
                        images[primitive.id]?.primitive = primitive
                        matrix(display, imageMatrix(primitive, asset), 1)
                    }
                    is Item -> matrix(display, primitive.transform, 1)
                    is Block -> matrix(display, Matrix4d(primitive.transform).translate(-0.5, -0.5, -0.5), 1)
                }
            }
            val textNodes = textNodes(menu.frontend).associateBy { it.properties.id }
            (animatedContentIds + animation.textContents.keys).forEach { id ->
                val node = textNodes[id] ?: return@forEach
                updateText(id, resolve(animation.textContents[id] ?: node.content))
            }
            (animatedOpacityIds + animation.textOpacities.keys).forEach { id ->
                val node = textNodes[id] ?: return@forEach
                updateTextOpacity(id, animation.textOpacities[id] ?: node.opacity)
            }
            animatedContentIds.clear()
            animatedContentIds += animation.textContents.keys
            animatedOpacityIds.clear()
            animatedOpacityIds += animation.textOpacities.keys
        }

        /**
         * Moves the already spawned editor entities to the draft's latest geometry. This deliberately
         * rejects topology changes: create/delete/property edits still use the full preview rebuild path.
         */
        fun updateEditorGeometry(menu: MenuDefinition, mode: MenuViewMode) {
            check(mode != MenuViewMode.RUNTIME) { "运行菜单不能使用编辑器几何更新" }
            val primitives = compiler.compile(menu.frontend)
            require(sceneDisplays.keys == primitives.mapTo(mutableSetOf()) { it.id }) {
                "编辑预览元素结构已改变，需要重建预览"
            }
            primitives.forEach { primitive ->
                val display = sceneDisplays.getValue(primitive.id)
                when (primitive) {
                    is Quad -> matrix(display, quadMatrix(primitive.transform, primitive.width, primitive.height), 1)
                    is Text -> matrix(display, textMatrix(primitive), 1)
                    is Image -> {
                        val source = imageValues[primitive.id] ?: primitive.node.source
                        val asset = requireNotNull(resources.resolve(source)) { "${primitive.id}: 图片资源已失效: $source" }
                        images[primitive.id]?.primitive = primitive
                        matrix(display, imageMatrix(primitive, asset), 1)
                    }
                    is Item -> matrix(display, primitive.transform, 1)
                    is Block -> matrix(display, Matrix4d(primitive.transform).translate(-0.5, -0.5, -0.5), 1)
                }
            }
            if (mode == MenuViewMode.BACKEND_PREVIEW) {
                require(overlays.keys == menu.backend.mapTo(mutableSetOf()) { it.id }) {
                    "编辑预览点击区域结构已改变，需要重建预览"
                }
                menu.backend.forEach { region ->
                    matrix(overlays.getValue(region.id),
                        quadMatrix(Matrix4d().translate(region.x, region.y, 0.0), region.width, region.height), 1)
                }
            } else {
                require(overlays.isEmpty()) { "前端编辑预览包含后端实体，需要重建预览" }
            }
        }

        fun updateText(id: String, content: String) {
            if (textValues[id] == content) {
                performance.metadataSkip()
                return
            }
            val display = texts[id] ?: return
            display.text(textComponent(content, textFonts.getValue(id)))
            textValues[id] = content
            performance.metadataWrite()
        }

        fun updateTextOpacity(id: String, opacity: Int) {
            val value = opacity.coerceIn(0, 255)
            if (textOpacities[id] == value) {
                performance.metadataSkip()
                return
            }
            texts[id]?.textOpacity = value.toByte()
            textOpacities[id] = value
            performance.metadataWrite()
        }

        fun updateImage(id: String, source: String) {
            if (imageValues[id] == source) {
                performance.metadataSkip()
                return
            }
            val handle = images[id] ?: return
            val asset = runCatching { resources.resolve(source) }.getOrNull() ?: return
            configureImage(handle.display, handle.primitive, asset)
            imageValues[id] = source
            performance.metadataWrite()
        }

        private fun configureImage(display: TextDisplay, primitive: Image, asset: ImageAsset) {
            val node = primitive.node
            display.text(Component.text(asset.character).font(Key.key(asset.font)))
            display.backgroundColor = Color.fromARGB(0)
            display.isDefaultBackground = false
            display.textOpacity = node.opacity.toByte()
            display.lineWidth = 32767
            display.alignment = TextDisplay.TextAlignment.CENTER
            display.isShadowed = false
            // CRServer character images use the normal text render type. The
            // see-through text shader blends the glyph through scene geometry
            // and makes an alpha-255 PNG look washed out against the menu.
            display.isSeeThrough = false
            matrix(display, imageMatrix(primitive, asset))
        }

        fun enableTooltip(style: TooltipStyle) {
            tooltipStyle = style
            tooltip = spawn(TextDisplay::class.java).also { display ->
                display.text(Component.empty())
                display.backgroundColor = Color.fromARGB(0)
                display.isDefaultBackground = false
                display.alignment = TextDisplay.TextAlignment.LEFT
                display.lineWidth = style.effectiveLineWidth
                display.isShadowed = false
                // Tooltip backgrounds must participate in the normal depth
                // test. The see-through text render type can reveal terrain
                // behind translucent world geometry (notably clear water),
                // producing an x-ray window through the configured backdrop.
                display.isSeeThrough = false
            }
            configureTooltipSkin(style.skin)
        }

        fun updateTooltipStyle(style: TooltipStyle) {
            tooltipStyle = style
            tooltip?.lineWidth = style.effectiveLineWidth
            configureTooltipSkin(style.skin)
            tooltipPoint = null
        }

        private fun configureTooltipSkin(skin: TooltipSkin?) {
            tooltipSkin?.displays?.values?.forEach(::removeDisplay)
            tooltipSkin = null
            if (skin == null) return
            val asset = requireNotNull(resources.resolveNineSlice(skin.request)) {
                "tooltip skin 尚未进入资源包，请执行 /arcmenu reload all：${skin.background}"
            }
            val displays = NineSlicePart.entries.associateWith {
                spawn(TextDisplay::class.java).also { display ->
                    display.text(Component.empty())
                    display.backgroundColor = Color.fromARGB(0)
                    display.isDefaultBackground = false
                    display.textOpacity = 255.toByte()
                    display.lineWidth = 32767
                    display.alignment = TextDisplay.TextAlignment.CENTER
                    display.isShadowed = false
                    display.isSeeThrough = false
                    if (shown) player.showEntity(plugin, display)
                }
            }
            tooltipSkin = TooltipSkinHandle(asset, displays)
        }

        fun configureCursor(style: CursorStyle?) {
            cursor?.let { display ->
                entities.remove(display)
                displayMatrices.remove(display.entityId)
                displayRotations.remove(display.entityId)
                interpolationPrimed.remove(display.entityId)
                display.remove()
                cursor = null
                cursorAsset = null
                cursorDefaultAsset = null
                cursorChooseAsset = null
            }
            if (style == null) return
            val asset = requireNotNull(resources.resolve(CURSOR_IMAGE_SOURCE)) {
                "鼠标模式指针图片不存在：images$CURSOR_IMAGE_SOURCE；请放入文件后执行 /arcmenu reload all"
            }
            val chooseAsset = requireNotNull(resources.resolve(CURSOR_CHOOSE_IMAGE_SOURCE)) {
                "鼠标模式选择指针图片不存在：images$CURSOR_CHOOSE_IMAGE_SOURCE；请放入文件后执行 /arcmenu reload all"
            }
            val display = spawn(TextDisplay::class.java)
            display.text(Component.text(asset.character).font(Key.key(asset.font)))
            display.backgroundColor = Color.fromARGB(0)
            display.isDefaultBackground = false
            display.textOpacity = 255.toByte()
            display.lineWidth = 32767
            display.alignment = TextDisplay.TextAlignment.CENTER
            display.isShadowed = false
            display.isSeeThrough = false
            matrix(display, cursorMatrix(MenuPoint(0.0, 0.0), style, asset))
            cursor = display
            cursorAsset = asset
            cursorDefaultAsset = asset
            cursorChooseAsset = chooseAsset
            if (shown) player.showEntity(plugin, display)
        }

        fun updateCursor(point: MenuPoint, style: CursorStyle, choosing: Boolean = false) {
            val display = cursor ?: return
            val asset = (if (choosing) cursorChooseAsset else cursorDefaultAsset) ?: return
            if (cursorAsset != asset) {
                display.text(Component.text(asset.character).font(Key.key(asset.font)))
                cursorAsset = asset
            }
            matrix(display, cursorMatrix(point, style, asset), 1)
        }

        /** The PNG's top-left arrow tip is the authoritative backend hit point. */
        private fun cursorMatrix(point: MenuPoint, style: CursorStyle, asset: ImageAsset): Matrix4dc {
            val height = style.size
            val width = height * asset.pixelWidth / asset.pixelHeight
            return Matrix4d()
                .translate(point.x + width / 2.0, point.y - height / 2.0, style.offsetZ)
                .scale(
                    width / (asset.glyphWidth * NATIVE_TEXT_PIXEL),
                    height / (asset.glyphHeight * NATIVE_TEXT_PIXEL),
                    1.0,
                )
        }

        fun updateTooltip(point: MenuPoint, lines: List<String>) {
            val display = tooltip ?: return
            val style = tooltipStyle ?: return
            val contentChanged = tooltipLines != lines
            if (contentChanged) {
                val components = lines.map { LegacyComponentSerializer.legacyAmpersand().deserialize(it) }
                display.text(Component.join(JoinConfiguration.newlines(), components))
                display.backgroundColor = Color.fromARGB(if (style.skin == null) style.backgroundArgb else 0)
                tooltipLines = lines.toList()
            }
            if (tooltipPoint != point || contentChanged) {
                val skin = style.skin
                val cornerX = point.x + style.offsetX
                val cornerY = point.y + style.offsetY
                val layout = skin?.let {
                    TooltipSkinLayout.layout(
                        cornerX,
                        cornerY,
                        style,
                        it,
                        TooltipMeasurer.box(lines, style.effectiveLineWidth, it),
                    )
                }
                val textOrigin = if (layout != null) {
                    layout.textOriginX to layout.textOriginY
                } else {
                    TooltipSkinLayout.plainTextOrigin(
                        cornerX,
                        cornerY,
                        style,
                        TooltipMeasurer.content(lines, style.effectiveLineWidth),
                    )
                }
                val local = Matrix4d().translate(
                    textOrigin.first,
                    textOrigin.second,
                    style.offsetZ + (skin?.textOffsetZ ?: 0.0),
                ).scale(style.size / 0.225)
                matrix(display, local)
                if (layout != null) updateTooltipSkin(layout, style)
                tooltipPoint = point
            }
        }

        private fun updateTooltipSkin(layout: TooltipLayout, style: TooltipStyle) {
            val skin = style.skin ?: return
            val handle = tooltipSkin ?: return
            if (!handle.visible) {
                handle.displays.forEach { (part, display) ->
                    val image = handle.asset.parts.getValue(part)
                    display.text(Component.text(image.character).font(Key.key(image.font)))
                    performance.metadataWrite()
                }
                handle.visible = true
            }
            handle.displays.forEach { (part, imageDisplay) ->
                val cell = layout.cells.getValue(part)
                val image = handle.asset.parts.getValue(part)
                val scaleX = cell.width / (image.glyphWidth * NATIVE_TEXT_PIXEL)
                val scaleY = cell.height / (image.glyphHeight * NATIVE_TEXT_PIXEL)
                // A one-character TextDisplay is centered by its advance, while the bitmap quad
                // uses its full cell width. Cancel the resulting half-pixel bearing per generated
                // slice so transparent outer columns cannot shift individual rows or columns.
                val glyphCenterX = 1.0 + (image.glyphWidth - image.glyphAdvance) / 2.0
                val transform = Matrix4d().translate(
                    cell.centerX - glyphCenterX * NATIVE_TEXT_PIXEL * scaleX,
                    cell.centerY,
                    style.offsetZ + skin.offsetZ,
                )
                    .scale(
                        scaleX,
                        scaleY,
                        1.0,
                    )
                matrix(imageDisplay, transform)
            }
        }

        fun hideTooltip() {
            val display = tooltip ?: return
            if (tooltipLines.isEmpty()) return
            display.text(Component.empty())
            display.backgroundColor = Color.fromARGB(0)
            tooltipSkin?.takeIf { it.visible }?.also { handle ->
                handle.displays.values.forEach { skinDisplay ->
                    skinDisplay.text(Component.empty())
                    performance.metadataWrite()
                }
                handle.visible = false
            }
            tooltipLines = emptyList()
            tooltipPoint = null
        }

        fun region(region: InteractionRegion) {
            // Keep diagnostic geometry on the actual hit plane. A depth offset would introduce parallax.
            overlays[region.id] = quad(Matrix4d().translate(region.x, region.y, 0.0), region.width, region.height, BLUE).apply {
                isSeeThrough = true
            }
        }

        fun select(id: String?) {
            if (id == selected) return
            overlays[selected]?.backgroundColor = Color.fromARGB(BLUE)
            overlays[id]?.backgroundColor = Color.fromARGB(RED)
            selected = id
        }

        /** Yellow cross rendered by the authoritative server coordinate probe. */
        fun mark(point: MenuPoint) {
            val pair = marker ?: (quad(Matrix4d(), 14.0, 1.2, YELLOW) to quad(Matrix4d(), 1.2, 14.0, YELLOW)).also {
                marker = it
                if (shown) {
                    player.showEntity(plugin, it.first)
                    player.showEntity(plugin, it.second)
                }
            }
            matrix(pair.first, Matrix4d().translate(point.x, point.y, 0.05).translate(-7.0, -0.6, 0.0)
                .scale(14.0, 1.2, 1.0).translate(0.4, 0.0, 0.0).scale(8.08, 3.66, 1.0))
            matrix(pair.second, Matrix4d().translate(point.x, point.y, 0.05).translate(-0.6, -7.0, 0.0)
                .scale(1.2, 14.0, 1.0).translate(0.4, 0.0, 0.0).scale(8.08, 3.66, 1.0))
        }

        private fun quad(transform: Matrix4dc, width: Double, height: Double, argb: Int): TextDisplay {
            val display = spawn(TextDisplay::class.java)
            display.text(Component.space())
            display.alignment = TextDisplay.TextAlignment.CENTER
            display.isDefaultBackground = false
            display.backgroundColor = Color.fromARGB(argb)
            display.isShadowed = false
            // Single-space background calibration used by FluxUI / libs-arc-ui; see THIRD_PARTY_NOTICES.
            // This is a reference visual calibration, not a substitute for the editor's human coordinate review.
            matrix(display, quadMatrix(transform, width, height))
            return display
        }

        private fun textMatrix(primitive: Text): Matrix4dc =
            Matrix4d(primitive.transform).scale(primitive.node.size / 0.225)

        private fun imageMatrix(primitive: Image, asset: ImageAsset): Matrix4dc {
            val width = primitive.node.width ?: asset.pixelWidth.toDouble()
            val height = primitive.node.height ?: asset.pixelHeight.toDouble()
            val scaleX = width / (asset.glyphWidth * NATIVE_TEXT_PIXEL)
            val scaleY = height / (asset.glyphHeight * NATIVE_TEXT_PIXEL)
            return Matrix4d(primitive.transform).scale(scaleX, scaleY, 1.0)
        }

        private fun quadMatrix(transform: Matrix4dc, width: Double, height: Double): Matrix4dc =
            Matrix4d(transform).translate(-width / 2, -height / 2, 0.0)
                .scale(width, height, 1.0).translate(0.4, 0.0, 0.0).scale(8.08, 3.66, 1.0)

        private fun matrix(display: Display, local: Matrix4dc, interpolationTicks: Int = 0) {
            val next = Matrix4f(Matrix4d(base).mul(local))
            require(next.isFinite) { "展示变换超出有效数值范围" }
            val previous = displayMatrices[display.entityId]
            if (previous != null && previous.equals(next, MATRIX_EPSILON)) {
                performance.matrixSkip()
                return
            }
            displayMatrices[display.entityId] = Matrix4f(next)
            val stable = StableDisplayTransforms.decompose(next)
            val previousRotation = displayRotations[display.entityId]
            // Minecraft decomposes arbitrary matrices with SVD before client interpolation. Near equal
            // singular values that decomposition is not unique and can turn scale-only motion into a
            // full rotation. Use a deterministic decomposition whenever possible and keep quaternion
            // signs continuous. Sheared frames retain the exact matrix but skip rotational interpolation.
            val interpolation = StableDisplayTransforms.interpolation(
                interpolationTicks,
                stable != null && (previous == null || previousRotation != null),
                display.entityId in interpolationPrimed,
            )
            if (interpolation.primed) interpolationPrimed += display.entityId else interpolationPrimed -= display.entityId
            display.interpolationDuration = interpolation.duration
            display.interpolationDelay = 0
            if (stable != null) {
                val rotation = StableDisplayTransforms.alignHemisphere(stable.rotation, previousRotation)
                displayRotations[display.entityId] = Quaternionf(rotation)
                display.transformation = Transformation(
                    stable.translation, rotation, stable.scale, Quaternionf(),
                )
            } else {
                displayRotations.remove(display.entityId)
                display.setTransformationMatrix(next)
            }
            performance.matrixWrite()
        }

        private fun textNodes(nodes: List<VisualNode>): List<TextNode> = buildList {
            fun visit(node: VisualNode) {
                when (node) {
                    is TextNode -> add(node)
                    is GroupNode -> node.children.forEach(::visit)
                    else -> Unit
                }
            }
            nodes.forEach(::visit)
        }

        private fun <T : Display> spawn(type: Class<T>): T {
            val display = player.world.spawn(anchor, type) {
                it.isVisibleByDefault = false
                it.isPersistent = false
                it.setGravity(false)
                it.isInvulnerable = true
                it.isSilent = true
                it.billboard = Display.Billboard.FIXED
                it.brightness = Display.Brightness(15, 15)
                it.interpolationDuration = 0
                it.teleportDuration = 0
            }
            entities += display
            performance.entitySpawn()
            return display
        }

        private fun removeDisplay(display: Display) {
            entities.remove(display)
            displayMatrices.remove(display.entityId)
            displayRotations.remove(display.entityId)
            interpolationPrimed.remove(display.entityId)
            display.remove()
        }

        fun show() {
            entities.forEach { player.showEntity(plugin, it) }
            shown = true
        }

        override fun close() {
            entities.forEach { it.remove() }
            entities.clear()
            overlays.clear()
            texts.clear()
            textValues.clear()
            textFonts.clear()
            textOpacities.clear()
            animatedContentIds.clear()
            animatedOpacityIds.clear()
            displayMatrices.clear()
            displayRotations.clear()
            interpolationPrimed.clear()
            sceneDisplays.clear()
            images.clear()
            imageValues.clear()
            tooltip = null
            tooltipSkin = null
            cursor = null
            cursorAsset = null
            cursorDefaultAsset = null
            cursorChooseAsset = null
            marker = null
            shown = false
        }

        private fun textComponent(content: String, font: String) =
            LegacyComponentSerializer.legacyAmpersand().deserialize(content).font(Key.key(font))

    }

    private fun itemMaterial(value: String): Material? = Material.matchMaterial(value)?.takeIf { it.isItem && !it.isAir }

    companion object {
        private const val BLUE: Int = 0x663B82F6
        private const val RED: Int = 0x88EF4444.toInt()
        private const val YELLOW: Int = 0xCCFACC15.toInt()
        private const val NATIVE_TEXT_PIXEL = 0.025
        const val CURSOR_IMAGE_SOURCE = "/mouse/mouse.png"
        const val CURSOR_CHOOSE_IMAGE_SOURCE = "/mouse/choose.png"
        private const val MATRIX_EPSILON = 1.0e-6f
    }
}
