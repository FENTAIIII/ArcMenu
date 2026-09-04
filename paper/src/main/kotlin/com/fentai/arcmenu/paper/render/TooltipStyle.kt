package com.fentai.arcmenu.paper.render

import com.fentai.arcmenu.paper.resource.NineSliceRequest
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import java.nio.file.Files
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class TooltipInsets(
    val left: Int,
    val right: Int,
    val top: Int,
    val bottom: Int,
)

data class TooltipSkin(
    val background: String,
    val frame: String?,
    val border: Int,
    val padding: TooltipInsets,
    val minWidth: Int,
    val minHeight: Int,
    val widthAdjust: Int = 0,
    val heightAdjust: Int = 0,
    val offsetX: Double = 0.0,
    val offsetY: Double = 0.0,
    val offsetZ: Double = -0.25,
    val scaleX: Double = 1.0,
    val scaleY: Double = 1.0,
    val textOffsetX: Double = 0.0,
    val textOffsetY: Double = 0.0,
    val textOffsetZ: Double = 0.0,
    val seamOverlapX: Double = 0.0,
    val seamOverlapY: Double = 0.0,
    val glyphOffsetX: Double = 0.0,
    val glyphOffsetY: Double = 0.0,
    val columnOffsets: Triple<Double, Double, Double> = Triple(0.0, 0.0, 0.0),
    val rowOffsets: Triple<Double, Double, Double> = Triple(0.0, 0.0, 0.0),
) {
    val request: NineSliceRequest get() = NineSliceRequest(background, frame, border)
}

enum class TooltipAnchor {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT;

    companion object {
        fun parse(value: String): TooltipAnchor = when (value.lowercase().replace('_', '-')) {
            "top-left" -> TOP_LEFT
            "top-right" -> TOP_RIGHT
            "bottom-left" -> BOTTOM_LEFT
            "bottom-right" -> BOTTOM_RIGHT
            else -> throw IllegalArgumentException(
                "tooltip anchor 只能为 top-left、top-right、bottom-left 或 bottom-right",
            )
        }
    }
}

data class TooltipStyle(
    val offsetX: Double,
    val offsetY: Double,
    val offsetZ: Double,
    val size: Double,
    val lineWidth: Int,
    val backgroundArgb: Int,
    val skin: TooltipSkin? = null,
    val anchor: TooltipAnchor = TooltipAnchor.BOTTOM_LEFT,
    val wrap: Boolean = false,
) {
    val effectiveLineWidth: Int get() = if (wrap) lineWidth else NO_WRAP_LINE_WIDTH

    private companion object { const val NO_WRAP_LINE_WIDTH = 32767 }
}

data class TooltipStyles(val touch: TooltipStyle, val mouse: TooltipStyle) {
    fun forMouse(enabled: Boolean): TooltipStyle = if (enabled) mouse else touch
    fun nineSliceRequests(): Set<NineSliceRequest> = listOfNotNull(touch.skin, mouse.skin).map { it.request }.toSet()
}

data class CursorStyle(
    val size: Double,
    val offsetZ: Double,
)

object TooltipStyleLoader {
    fun load(plugin: Plugin): TooltipStyles {
        val file = plugin.dataFolder.toPath().resolve("tooltip.yml")
        if (!Files.exists(file)) plugin.saveResource("tooltip.yml", false)
        val yaml = YamlConfiguration.loadConfiguration(file.toFile())
        return from(yaml)
    }

    internal fun from(yaml: FileConfiguration): TooltipStyles {
        fun style(section: String, defaults: TooltipStyle): TooltipStyle {
            val offset = yaml.getConfigurationSection("$section.offset")
            val skinSection = yaml.getConfigurationSection("$section.skin")
            val skin = skinSection?.let { loadSkin(section, it) }
            val wrapPath = "$section.wrap"
            val wrap = if (yaml.contains(wrapPath)) {
                require(yaml.get(wrapPath) is Boolean) { "tooltip.yml $wrapPath 必须为 true 或 false" }
                yaml.getBoolean(wrapPath)
            } else {
                false
            }
            val anchor = TooltipAnchor.parse(yaml.getString("$section.anchor", "bottom-left")!!)
            val result = TooltipStyle(
                offsetX = offset?.getDouble("x", defaults.offsetX) ?: defaults.offsetX,
                offsetY = offset?.getDouble("y", defaults.offsetY) ?: defaults.offsetY,
                offsetZ = offset?.getDouble("z", defaults.offsetZ) ?: defaults.offsetZ,
                size = yaml.getDouble("$section.size", defaults.size),
                lineWidth = yaml.getInt("$section.line-width", defaults.lineWidth),
                backgroundArgb = color(yaml.getString("$section.background", String.format("#%08X", defaults.backgroundArgb))!!),
                skin = skin,
                anchor = anchor,
                wrap = wrap,
            )
            require(listOf(result.offsetX, result.offsetY, result.offsetZ, result.size).all(Double::isFinite)) {
                "tooltip.yml $section 包含非有限数字"
            }
            require(result.size > 0.0) { "tooltip.yml $section.size 必须为有限正数" }
            require(result.lineWidth > 0) { "tooltip.yml $section.line-width 必须大于 0" }
            return result
        }
        val touchDefaults = TooltipStyle(10.0, -10.0, 3.0, 7.0, 180, 0xD0101010.toInt())
        val touchSection = if (yaml.isConfigurationSection("touch")) "touch" else "crosshair"
        val touch = style(touchSection, touchDefaults)
        // touch/mouse are the canonical mode names. crosshair/cursor remain accepted for files
        // created before the modes were named consistently. A missing mouse section inherits the
        // complete touch style, including its skin and all tuning values.
        val mouseSection = when {
            yaml.isConfigurationSection("mouse") -> "mouse"
            yaml.isConfigurationSection("cursor") -> "cursor"
            else -> null
        }
        val mouse = mouseSection?.let {
            style(it, TooltipStyle(8.0, -8.0, 6.0, 7.0, 180, 0xD0101010.toInt()))
        } ?: touch
        return TooltipStyles(touch, mouse)
    }

    private fun loadSkin(styleName: String, section: ConfigurationSection): TooltipSkin {
        val background = requireNotNull(section.getString("background")) {
            "tooltip.yml $styleName.skin.background 不能为空"
        }
        val frame = section.getString("frame")?.takeIf(String::isNotBlank)
        val border = section.getInt("border", 8)
        val paddingSection = section.getConfigurationSection("padding")
        val legacyPaddingX = paddingSection?.getInt("x", border) ?: border
        val legacyPaddingY = paddingSection?.getInt("y", border) ?: border
        val padding = TooltipInsets(
            paddingSection?.getInt("left", legacyPaddingX) ?: legacyPaddingX,
            paddingSection?.getInt("right", legacyPaddingX) ?: legacyPaddingX,
            paddingSection?.getInt("top", legacyPaddingY) ?: legacyPaddingY,
            paddingSection?.getInt("bottom", legacyPaddingY) ?: legacyPaddingY,
        )
        val minimum = section.getConfigurationSection("min-size")
        val minWidth = minimum?.getInt("width", border * 2 + 1) ?: border * 2 + 1
        val minHeight = minimum?.getInt("height", border * 2 + 1) ?: border * 2 + 1
        val sizeAdjust = section.getConfigurationSection("size-adjust")
        val skinOffset = section.getConfigurationSection("offset")
        val skinScale = section.getConfigurationSection("scale")
        val textOffset = section.getConfigurationSection("text-offset")
        val seamOverlap = section.getConfigurationSection("seam-overlap")
        val glyphOffset = section.getConfigurationSection("glyph-offset")
        val columnOffset = section.getConfigurationSection("column-offset")
        val rowOffset = section.getConfigurationSection("row-offset")
        val skin = TooltipSkin(
            background = background,
            frame = frame,
            border = border,
            padding = padding,
            minWidth = minWidth,
            minHeight = minHeight,
            widthAdjust = sizeAdjust?.getInt("width", 0) ?: 0,
            heightAdjust = sizeAdjust?.getInt("height", 0) ?: 0,
            offsetX = skinOffset?.getDouble("x", 0.0) ?: 0.0,
            offsetY = skinOffset?.getDouble("y", 0.0) ?: 0.0,
            offsetZ = skinOffset?.getDouble("z", -0.25) ?: -0.25,
            scaleX = skinScale?.getDouble("x", 1.0) ?: 1.0,
            scaleY = skinScale?.getDouble("y", 1.0) ?: 1.0,
            textOffsetX = textOffset?.getDouble("x", 0.0) ?: 0.0,
            textOffsetY = textOffset?.getDouble("y", 0.0) ?: 0.0,
            textOffsetZ = textOffset?.getDouble("z", 0.0) ?: 0.0,
            seamOverlapX = seamOverlap?.getDouble("x", 0.0) ?: 0.0,
            seamOverlapY = seamOverlap?.getDouble("y", 0.0) ?: 0.0,
            glyphOffsetX = glyphOffset?.getDouble("x", 0.0) ?: 0.0,
            glyphOffsetY = glyphOffset?.getDouble("y", 0.0) ?: 0.0,
            columnOffsets = Triple(
                columnOffset?.getDouble("left", 0.0) ?: 0.0,
                columnOffset?.getDouble("center", 0.0) ?: 0.0,
                columnOffset?.getDouble("right", 0.0) ?: 0.0,
            ),
            rowOffsets = Triple(
                rowOffset?.getDouble("top", 0.0) ?: 0.0,
                rowOffset?.getDouble("center", 0.0) ?: 0.0,
                rowOffset?.getDouble("bottom", 0.0) ?: 0.0,
            ),
        )
        require(imagePath(background)) { "tooltip.yml $styleName.skin.background 必须为 images 下的小写 PNG 虚拟路径" }
        require(frame == null || imagePath(frame)) { "tooltip.yml $styleName.skin.frame 必须为 images 下的小写 PNG 虚拟路径" }
        require(border > 0) { "tooltip.yml $styleName.skin.border 必须大于 0" }
        require(listOf(padding.left, padding.right, padding.top, padding.bottom).all { it >= 0 }) {
            "tooltip.yml $styleName.skin.padding 不能为负数"
        }
        require(minWidth >= border * 2 + 1 && minHeight >= border * 2 + 1) {
            "tooltip.yml $styleName.skin.min-size 必须至少容纳两侧 border"
        }
        val finiteValues = listOf(
            skin.offsetX, skin.offsetY, skin.offsetZ, skin.scaleX, skin.scaleY,
            skin.textOffsetX, skin.textOffsetY, skin.textOffsetZ,
            skin.seamOverlapX, skin.seamOverlapY, skin.glyphOffsetX, skin.glyphOffsetY,
            skin.columnOffsets.first, skin.columnOffsets.second, skin.columnOffsets.third,
            skin.rowOffsets.first, skin.rowOffsets.second, skin.rowOffsets.third,
        )
        require(finiteValues.all(Double::isFinite)) { "tooltip.yml $styleName.skin 包含非有限数字" }
        require(skin.scaleX > 0.0 && skin.scaleY > 0.0) {
            "tooltip.yml $styleName.skin.scale 必须为有限正数"
        }
        require(skin.seamOverlapX >= 0.0 && skin.seamOverlapY >= 0.0) {
            "tooltip.yml $styleName.skin.seam-overlap 不能为负数"
        }
        require(minWidth + skin.widthAdjust >= border * 2 + 1 && minHeight + skin.heightAdjust >= border * 2 + 1) {
            "tooltip.yml $styleName.skin.size-adjust 不能让 min-size 小于两侧 border"
        }
        return skin
    }

    private fun imagePath(value: String): Boolean {
        val path = value.replace('\\', '/')
        return Regex("/[a-z0-9_./-]+\\.png").matches(path) && !path.contains("//") &&
            path.split('/').none { it == "." || it == ".." }
    }

    private fun color(value: String): Int {
        require(Regex("#[0-9a-fA-F]{8}").matches(value)) { "tooltip.yml background 必须为 #AARRGGBB" }
        return value.substring(1).toLong(16).toInt()
    }
}

internal data class TooltipBox(
    val widthPixels: Int,
    val heightPixels: Int,
    val contentWidthPixels: Int,
    val contentHeightPixels: Int,
)

internal data class TooltipContentSize(val widthPixels: Int, val heightPixels: Int)

/** Conservative client-font measurement used only to size the optional skin behind TextDisplay text. */
internal object TooltipMeasurer {
    // TextDisplay uses Font.lineHeight + 1 between lines and subtracts the final gap.
    private const val LINE_ADVANCE = 10

    fun box(lines: List<String>, lineWidth: Int, skin: TooltipSkin): TooltipBox {
        val content = content(lines, lineWidth)
        val width = maxOf(
            skin.minWidth,
            content.widthPixels + skin.padding.left + skin.padding.right,
            skin.border * 2 + 1,
        ) + skin.widthAdjust
        val height = maxOf(
            skin.minHeight,
            content.heightPixels + skin.padding.top + skin.padding.bottom,
            skin.border * 2 + 1,
        ) + skin.heightAdjust
        return TooltipBox(
            maxOf(width, skin.border * 2 + 1),
            maxOf(height, skin.border * 2 + 1),
            content.widthPixels,
            content.heightPixels,
        )
    }

    fun content(lines: List<String>, lineWidth: Int): TooltipContentSize {
        var widest = 0
        var visualLines = 0
        lines.ifEmpty { listOf("") }.forEach { source ->
            val width = width(stripLegacy(source))
            widest = max(widest, min(width, lineWidth))
            visualLines += max(1, ceil(width.toDouble() / lineWidth).toInt())
        }
        return TooltipContentSize(widest, visualLines * LINE_ADVANCE - 1)
    }

    private fun stripLegacy(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if ((character == '&' || character == '§') && index + 1 < value.length &&
                value[index + 1].lowercaseChar() in "0123456789abcdefklmnorx") {
                index += 2
            } else {
                append(character)
                index++
            }
        }
    }

    private fun width(value: String): Int {
        var result = 0
        var index = 0
        while (index < value.length) {
            val codepoint = value.codePointAt(index)
            result += glyphWidth(codepoint)
            index += Character.charCount(codepoint)
        }
        return result
    }

    private fun glyphWidth(codepoint: Int): Int = when {
        codepoint == ' '.code -> 4
        codepoint == '\t'.code -> 16
        codepoint >= 0x2E80 -> 9
        codepoint.toChar() in "i!.,:;'|`" -> 2
        codepoint.toChar() in "ltI[](){}" -> 4
        codepoint.toChar() in "fjkr" -> 5
        else -> 6
    }
}
