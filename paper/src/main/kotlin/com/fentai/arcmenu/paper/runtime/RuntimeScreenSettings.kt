package com.fentai.arcmenu.paper.runtime

import com.fentai.arcmenu.core.model.Canvas
import com.fentai.arcmenu.core.model.Vec3
import com.fentai.arcmenu.paper.input.PointerMode
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import java.nio.file.Files

data class ScreenPlacement(
    val distance: Double = DEFAULT_SCREEN_DISTANCE,
    val offset: Vec3 = Vec3(),
)

/** Global physical placement for the two pointer modes. */
data class RuntimeScreenSettings(
    val touch: ScreenPlacement = ScreenPlacement(),
    val mouse: ScreenPlacement = ScreenPlacement(),
) {
    fun forMode(mode: PointerMode): ScreenPlacement = if (mode == PointerMode.MOUSE) mouse else touch
}

object RuntimeScreenSettingsLoader {
    fun load(plugin: Plugin): RuntimeScreenSettings {
        val file = plugin.dataFolder.toPath().resolve("offset.yml")
        if (!Files.exists(file)) plugin.saveResource("offset.yml", false)
        return from(YamlConfiguration.loadConfiguration(file.toFile()))
    }

    internal fun from(config: FileConfiguration): RuntimeScreenSettings {
        config.getKeys(false).firstOrNull { it !in setOf("touch", "mouse") }?.let {
            throw IllegalArgumentException("offset.yml $it 是不支持的字段")
        }

        fun number(path: String, fallback: Double): Double = config.get(path)?.let { value ->
            (value as? Number)?.toDouble() ?: throw IllegalArgumentException("offset.yml $path 必须为数字")
        } ?: fallback

        fun placement(section: String): ScreenPlacement {
            config.getConfigurationSection(section)?.getKeys(false)
                ?.firstOrNull { it !in setOf("distance", "offset") }
                ?.let { throw IllegalArgumentException("offset.yml $section.$it 是不支持的字段") }
            config.getConfigurationSection("$section.offset")?.getKeys(false)
                ?.firstOrNull { it !in setOf("x", "y", "z") }
                ?.let { throw IllegalArgumentException("offset.yml $section.offset.$it 是不支持的字段") }

            val distance = number("$section.distance", DEFAULT_SCREEN_DISTANCE)
            val offset = Vec3(
                number("$section.offset.x", 0.0),
                number("$section.offset.y", 0.0),
                number("$section.offset.z", 0.0),
            )
            require(distance.isFinite() && distance in 0.1..16.0) {
                "offset.yml $section.distance 必须介于 0.1 和 16.0"
            }
            require(listOf(offset.x, offset.y, offset.z).all(Double::isFinite)) {
                "offset.yml $section.offset 必须为有限数字"
            }
            require(listOf(offset.x, offset.y, offset.z).all { it in -16.0..16.0 }) {
                "offset.yml $section.offset 的各轴必须介于 -16.0 和 16.0"
            }
            return ScreenPlacement(distance, offset)
        }

        return RuntimeScreenSettings(placement("touch"), placement("mouse"))
    }
}

/**
 * Moves an authored canvas to the near-eye runtime plane while preserving its
 * exact apparent size: pixelsPerBlock * distance remains constant.
 */
internal fun Canvas.forRuntimeScreen(placement: ScreenPlacement): Canvas {
    val normalizedPixelsPerBlock = pixelsPerBlock * distance / placement.distance
    require(normalizedPixelsPerBlock.isFinite() && normalizedPixelsPerBlock > 0.0) {
        "运行屏幕换算后的 pixels-per-block 必须为有限正数"
    }
    return copy(pixelsPerBlock = normalizedPixelsPerBlock, distance = placement.distance)
}

private const val DEFAULT_SCREEN_DISTANCE = 0.65
