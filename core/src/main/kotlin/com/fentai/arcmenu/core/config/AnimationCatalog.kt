package com.fentai.arcmenu.core.config

import com.fentai.arcmenu.core.animation.*
import com.fentai.arcmenu.core.behavior.*
import com.fentai.arcmenu.core.model.*
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Files
import java.nio.file.Path

data class AnimationLoadResult(val applied: Boolean, val menus: Int, val tracks: Int, val errors: List<String>)

/** Loads the single animations.yml atomically and validates every target against the candidate menu set. */
class AnimationCatalog {
    var configuration: AnimationConfiguration = AnimationConfiguration()
        private set

    fun reload(file: Path, menus: Map<String, MenuDocument>): AnimationLoadResult {
        val candidate = try {
            parse(if (Files.exists(file)) Files.readString(file) else "schema-version: 1", menus, file.fileName.toString())
        } catch (error: Exception) {
            return AnimationLoadResult(false, configuration.menus.size, configuration.menus.values.sumOf { it.tracks.size }, listOf(error.message ?: error.javaClass.simpleName))
        }
        configuration = candidate
        return AnimationLoadResult(true, candidate.menus.size, candidate.menus.values.sumOf { it.tracks.size }, emptyList())
    }

    fun parse(source: String, menus: Map<String, MenuDocument>, sourceName: String = "animations.yml"): AnimationConfiguration {
        val options = LoaderOptions().apply { isAllowDuplicateKeys = false }
        val loaded = try { Yaml(SafeConstructor(options)).load<Any?>(source) ?: emptyMap<String, Any?>() } catch (error: Exception) {
            throw MenuFormatException("$sourceName: ${error.message}")
        }
        val root = Reader(loaded, sourceName)
        root.only("schema-version", "transitions", "tracks", "menus")
        if (root.int("schema-version", 1) != 1) root.fail("schema-version", "仅支持版本 1")

        val transitions = linkedMapOf<String, MenuTransition>()
        root.obj("transitions").entries().forEach { (id, reader) ->
            identifier(id, reader)
            reader.only("enter", "exit", "switch")
            fun phase(name: String): TransitionPhase? {
                if (!reader.has(name)) return null
                val phase = reader.obj(name)
                phase.only("duration", "easing", "offset", "scale")
                val offset = phase.vector("offset", Vec3())
                val scale = phase.scale("scale", 1.0, 1.0)
                return TransitionPhase(
                    durationTicks = phase.positiveInt("duration"),
                    easing = phase.easing(),
                    pose = TransitionPose(offset, scale.first, scale.second),
                )
            }
            val transition = MenuTransition(phase("enter"), phase("exit"), phase("switch"))
            if (transition.enter == null && transition.exit == null && transition.switch == null) reader.fail("", "过渡至少需要 enter、exit 或 switch")
            transitions[id] = transition
        }

        val tracks = linkedMapOf<String, AnimationTrack>()
        root.obj("tracks").entries().forEach { (id, reader) ->
            identifier(id, reader)
            reader.only("target", "property", "duration", "easing", "loop", "trigger", "keyframes")
            val property = try {
                TrackProperty.valueOf(reader.string("property").uppercase().replace('-', '_'))
            } catch (_: IllegalArgumentException) {
                reader.fail("property", "只能为 offset、rotation、scale、content 或 opacity")
            }
            val frames = reader.list("keyframes").mapIndexed { index, value ->
                val frame = Reader(value, "${reader.path}.keyframes[$index]")
                frame.only("at", "value")
                val at = frame.number("at")
                if (at !in 0.0..1.0) frame.fail("at", "必须介于 0 和 1")
                val frameValue: KeyframeValue = when (property) {
                    TrackProperty.OFFSET, TrackProperty.ROTATION -> VectorFrameValue(frame.vector("value", null))
                    TrackProperty.SCALE -> frame.scale("value", null, null).let { ScaleFrameValue(it.first, it.second) }
                    TrackProperty.CONTENT -> ContentFrameValue(frame.string("value"))
                    TrackProperty.OPACITY -> OpacityFrameValue(frame.int("value").also { if (it !in 0..255) frame.fail("value", "必须介于 0 和 255") })
                }
                AnimationKeyframe(at, frameValue)
            }
            if (frames.size < 2) reader.fail("keyframes", "至少需要两个关键帧")
            if (frames.zipWithNext().any { (a, b) -> a.at >= b.at }) reader.fail("keyframes", "at 必须严格递增且不能重复")
            if (frames.first().at != 0.0 || frames.last().at != 1.0) reader.fail("keyframes", "必须从 at: 0 开始并以 at: 1 结束")
            val loop = when (val value = reader.string("loop", "once").lowercase().replace('_', '-')) {
                "once" -> TrackLoop.ONCE
                "repeat" -> TrackLoop.REPEAT
                "ping-pong", "pingpong" -> TrackLoop.PING_PONG
                else -> reader.fail("loop", "只能为 once、repeat 或 ping-pong")
            }
            val trigger = when (reader.string("trigger", "open").lowercase()) {
                "open" -> TrackTrigger.OPEN
                "api" -> TrackTrigger.API
                else -> reader.fail("trigger", "当前支持 open 或 api")
            }
            tracks[id] = AnimationTrack(
                id, reader.string("target"), property, reader.positiveInt("duration"),
                reader.easing(), loop, trigger, frames,
            )
        }

        val bindings = linkedMapOf<String, MenuAnimationBinding>()
        root.obj("menus").entries().forEach { (menuId, reader) ->
            reader.only("transition", "tracks")
            val menu = menus[menuId]?.definition ?: reader.fail("", "绑定了不存在的菜单 $menuId")
            val transition = if (reader.has("transition")) transitions[reader.string("transition")]
                ?: reader.fail("transition", "引用了不存在的过渡") else null
            val selected = linkedMapOf<String, AnimationTrack>()
            reader.strings("tracks").forEach { trackId ->
                val track = tracks[trackId] ?: reader.fail("tracks", "引用了不存在的轨道 $trackId")
                if (selected.putIfAbsent(trackId, track) != null) reader.fail("tracks", "重复轨道 $trackId")
            }
            val nodes = visualNodes(menu.frontend)
            selected.values.forEach { track ->
                val target = nodes[track.target] ?: reader.fail("tracks", "轨道 ${track.id} 的目标 ${track.target} 不在菜单 $menuId 中")
                if (track.property in setOf(TrackProperty.CONTENT, TrackProperty.OPACITY) && target !is TextNode) {
                    reader.fail("tracks", "轨道 ${track.id} 的 ${track.property.name.lowercase()} 只允许文本节点；${track.target} 是 ${target.javaClass.simpleName}")
                }
            }
            val duplicateOpenOwner = selected.values.filter { it.trigger == TrackTrigger.OPEN }
                .groupBy { it.target to it.property }.entries.firstOrNull { it.value.size > 1 }
            if (duplicateOpenOwner != null) reader.fail("tracks", "open 轨道 ${duplicateOpenOwner.value.joinToString { it.id }} 同时拥有 ${duplicateOpenOwner.key.first}.${duplicateOpenOwner.key.second.name.lowercase()}")
            bindings[menuId] = MenuAnimationBinding(transition, selected)
        }
        menus.forEach { (menuId, document) ->
            val available = bindings[menuId]?.tracks?.keys ?: emptySet()
            val actions = buildList {
                document.definition.events.open.forEach { addAll(it.actions + it.deny) }
                document.definition.events.close.forEach { addAll(it.actions + it.deny) }
                document.definition.backend.forEach { region ->
                    addAll(region.actions.allActions().toList())
                    addAll(region.deny)
                }
            }.flatMap(::leafActions)
            actions.forEach { action ->
                val id = when (action) {
                    is PlayAnimationAction -> action.animationId
                    is StopAnimationAction -> action.animationId
                    else -> return@forEach
                }
                if ('%' !in id && '{' !in id && id !in available) {
                    throw MenuFormatException("$sourceName.menus.$menuId: 动作引用了未绑定的动画轨道 $id")
                }
            }
        }
        return AnimationConfiguration(bindings)
    }

    private fun identifier(id: String, reader: Reader) {
        if (!Regex("[a-z0-9][a-z0-9_-]*").matches(id)) reader.fail("", "ID 只能使用小写字母、数字、下划线或连字符")
    }

    private fun visualNodes(nodes: List<VisualNode>): Map<String, VisualNode> = buildMap {
        fun visit(node: VisualNode) {
            put(node.properties.id, node)
            if (node is GroupNode) node.children.forEach(::visit)
        }
        nodes.forEach(::visit)
    }

    private fun leafActions(action: MenuAction): List<MenuAction> = when (action) {
        is ConfiguredAction -> leafActions(action.action)
        is ActionLanguage.SequenceAction -> action.actions.flatMap(::leafActions)
        else -> listOf(action)
    }

    private class Reader(value: Any?, val path: String) {
        private val values: Map<String, Any?> = (value as? Map<*, *>)?.let { map ->
            if (map.keys.any { it !is String }) fail("", "对象键必须为字符串")
            @Suppress("UNCHECKED_CAST") (map as Map<String, Any?>)
        } ?: fail("", "必须为 YAML 对象")

        fun fail(key: String, message: String): Nothing = throw MenuFormatException("$path${if (key.isBlank()) "" else ".$key"}: $message")
        fun only(vararg allowed: String) = values.keys.firstOrNull { it !in allowed }?.let { fail(it, "不支持的字段") }
        fun has(key: String) = key in values
        fun obj(key: String) = Reader(if (has(key)) values[key] else emptyMap<String, Any?>(), "$path.$key")
        fun entries() = values.map { (key, value) -> key to Reader(value, "$path.$key") }
        fun list(key: String): List<Any?> = if (!has(key)) emptyList() else values[key] as? List<*> ?: fail(key, "必须为列表")
        fun strings(key: String): List<String> {
            val list = list(key)
            return list.mapIndexed { index, value -> value as? String ?: fail("$key[$index]", "必须为字符串") }
        }
        fun string(key: String, default: String? = null): String =
            if (!has(key) && default != null) default else values[key] as? String ?: fail(key, "必须为字符串")
        fun number(key: String, default: Double? = null): Double {
            if (!has(key) && default != null) return default
            return (values[key] as? Number)?.toDouble()?.takeIf(Double::isFinite) ?: fail(key, "必须为有限数字")
        }
        fun int(key: String, default: Int? = null): Int {
            val value = number(key, default?.toDouble())
            if (value % 1.0 != 0.0 || value !in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) fail(key, "必须为整数")
            return value.toInt()
        }
        fun positiveInt(key: String): Int = int(key).also { if (it !in 1..72_000) fail(key, "必须介于 1 和 72000 tick") }
        fun easing(): Easing = when (string("easing", "linear").lowercase().replace('_', '-')) {
            "linear" -> Easing.LINEAR
            "ease-in" -> Easing.EASE_IN
            "ease-out" -> Easing.EASE_OUT
            "ease-in-out" -> Easing.EASE_IN_OUT
            else -> fail("easing", "只能为 linear、ease-in、ease-out 或 ease-in-out")
        }
        fun vector(key: String, default: Vec3?): Vec3 {
            if (!has(key) && default != null) return default
            val reader = obj(key)
            reader.only("x", "y", "z")
            val value = Vec3(reader.number("x", 0.0), reader.number("y", 0.0), reader.number("z", 0.0))
            if (listOf(value.x, value.y, value.z).any { kotlin.math.abs(it) > 100_000.0 }) reader.fail("", "分量绝对值不能超过 100000")
            return value
        }
        fun scale(key: String, defaultX: Double?, defaultY: Double?): Pair<Double, Double> {
            if (!has(key) && defaultX != null && defaultY != null) return defaultX to defaultY
            val reader = obj(key)
            reader.only("x", "y")
            val x = reader.number("x", defaultX)
            val y = reader.number("y", defaultY)
            if (x == 0.0 || y == 0.0 || kotlin.math.abs(x) > 1000.0 || kotlin.math.abs(y) > 1000.0) reader.fail("", "缩放不能为 0，绝对值不能超过 1000")
            return x to y
        }
    }
}
