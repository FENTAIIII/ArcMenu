package com.fentai.arcmenu.core.config

import com.fentai.arcmenu.core.animation.*
import com.fentai.arcmenu.core.behavior.*
import com.fentai.arcmenu.core.model.*
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs

data class AnimationLoadResult(val applied: Boolean, val menus: Int, val tracks: Int, val errors: List<String>)

/** Atomically loads the main configuration and namespaced portable-package fragments. */
class AnimationCatalog {
    @Volatile var configuration: AnimationConfiguration = AnimationConfiguration()
        private set

    fun reload(file: Path, menus: Map<String, MenuDocument>): AnimationLoadResult {
        val candidate = try {
            val fragmentDirectory = file.toAbsolutePath().parent.resolve("animations")
            val fragments = if (!Files.isDirectory(fragmentDirectory)) emptyList() else Files.list(fragmentDirectory).use { files ->
                files.filter { Files.isRegularFile(it) && Regex("arcpack-[a-z0-9][a-z0-9_-]{0,63}\\.yml").matches(it.fileName.toString()) }.sorted().toList()
            }
            require(fragments.size <= 1024) { "Too many package animation configurations" }
            val sources = fragments.map { path ->
                require(path.toRealPath().startsWith(fragmentDirectory.toRealPath()) && Files.size(path) <= 2_000_000) { "Package animation configuration is unsafe or exceeds 2 MB" }
                val source = Files.readString(path)
                val root = strictYaml().load<Any?>(source) as? Map<*, *>
                    ?: throw MenuFormatException("$path: Animation configuration must be an object")
                val bound = (root["menus"] as? Map<*, *>)?.keys?.map { it as? String ?: throw MenuFormatException("$path: Menu IDs must be strings") }?.toSet().orEmpty()
                require(bound.all { it in menus }) { "$path: Package animation references a nonexistent menu" }
                Triple(path, source, bound)
            }
            val packageMenus = sources.flatMap { it.third }.toSet()
            val bindings = parse(if (Files.exists(file)) Files.readString(file) else "schema-version: 1",
                menus.filterKeys { it !in packageMenus }, file.fileName.toString()).menus.toMutableMap()
            sources.forEach { (path, source, bound) ->
                val configuration = parse(source, menus.filterKeys { it in bound }, path.fileName.toString())
                require(configuration.menus.keys.none(bindings::containsKey)) { "$path: Duplicate menu animation binding" }
                bindings.putAll(configuration.menus)
            }
            AnimationConfiguration(bindings)
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
        if (root.int("schema-version", 1) != 1) root.fail("schema-version", "Only version 1 is supported")

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
                    pose = TransitionPose(offset, scale.x, scale.y),
                )
            }
            val transition = MenuTransition(phase("enter"), phase("exit"), phase("switch"))
            if (transition.enter == null && transition.exit == null && transition.switch == null) reader.fail("", "Transition requires at least one of enter, exit, or switch")
            transitions[id] = transition
        }

        val tracks = linkedMapOf<String, AnimationTrack>()
        root.obj("tracks").entries().forEach { (id, reader) ->
            identifier(id, reader)
            reader.only("target", "property", "duration", "easing", "loop", "trigger", "keyframes")
            val property = try {
                TrackProperty.valueOf(reader.string("property").uppercase().replace('-', '_'))
            } catch (_: IllegalArgumentException) {
                reader.fail("property", "Must be offset, rotation, scale, content, or opacity")
            }
            val frames = reader.list("keyframes").mapIndexed { index, value ->
                val frame = Reader(value, "${reader.path}.keyframes[$index]")
                frame.only("at", "value")
                val at = frame.number("at")
                if (at !in 0.0..1.0) frame.fail("at", "Must be between 0 and 1")
                val frameValue: KeyframeValue = when (property) {
                    TrackProperty.OFFSET, TrackProperty.ROTATION -> VectorFrameValue(frame.vector("value", null))
                    TrackProperty.SCALE -> frame.scale("value", null, null, allowZ = true)
                    TrackProperty.CONTENT -> ContentFrameValue(frame.string("value"))
                    TrackProperty.OPACITY -> OpacityFrameValue(frame.int("value").also { if (it !in 0..255) frame.fail("value", "Must be between 0 and 255") })
                }
                AnimationKeyframe(at, frameValue)
            }
            if (frames.size < 2) reader.fail("keyframes", "At least two keyframes are required")
            if (frames.zipWithNext().any { (a, b) -> a.at >= b.at }) reader.fail("keyframes", "at values must be strictly increasing without duplicates")
            if (frames.first().at != 0.0 || frames.last().at != 1.0) reader.fail("keyframes", "Must start at at: 0 and end at at: 1")
            val loop = when (val value = reader.string("loop", "once").lowercase().replace('_', '-')) {
                "once" -> TrackLoop.ONCE
                "repeat" -> TrackLoop.REPEAT
                "ping-pong", "pingpong" -> TrackLoop.PING_PONG
                else -> reader.fail("loop", "Must be once, repeat, or ping-pong")
            }
            val trigger = when (reader.string("trigger", "open").lowercase()) {
                "open" -> TrackTrigger.OPEN
                "api" -> TrackTrigger.API
                else -> reader.fail("trigger", "Currently supports open or api")
            }
            tracks[id] = AnimationTrack(
                id, reader.string("target"), property, reader.positiveInt("duration"),
                reader.easing(), loop, trigger, frames,
            )
        }

        val bindings = linkedMapOf<String, MenuAnimationBinding>()
        root.obj("menus").entries().forEach { (menuId, reader) ->
            reader.only("transition", "tracks")
            val menu = menus[menuId]?.definition ?: reader.fail("", "Binding references a nonexistent menu $menuId")
            val transition = if (reader.has("transition")) transitions[reader.string("transition")]
                ?: reader.fail("transition", "References a nonexistent transition") else null
            val selected = linkedMapOf<String, AnimationTrack>()
            reader.strings("tracks").forEach { trackId ->
                val track = tracks[trackId] ?: reader.fail("tracks", "References a nonexistent track $trackId")
                if (selected.putIfAbsent(trackId, track) != null) reader.fail("tracks", "Duplicate track $trackId")
            }
            val nodes = visualNodes(menu.frontend)
            selected.values.forEach { track ->
                val target = nodes[track.target] ?: reader.fail("tracks", "Target ${track.target} of track ${track.id} is not in menu $menuId")
                if (track.property in setOf(TrackProperty.CONTENT, TrackProperty.OPACITY) && target !is TextNode) {
                    reader.fail("tracks", "Track ${track.id} property ${track.property.name.lowercase()} only supports text nodes; ${track.target} is ${target.javaClass.simpleName}")
                }
                if (track.keyframes.any { (it.value as? ScaleFrameValue)?.z != null } && target !is ItemNode && target !is BlockNode) {
                    reader.fail("tracks", "Track ${track.id} scale.z only supports item or block nodes")
                }
            }
            val duplicateOpenOwner = selected.values.filter { it.trigger == TrackTrigger.OPEN }
                .groupBy { it.target to it.property }.entries.firstOrNull { it.value.size > 1 }
            if (duplicateOpenOwner != null) reader.fail("tracks", "open tracks ${duplicateOpenOwner.value.joinToString { it.id }} have conflicting ownership of ${duplicateOpenOwner.key.first}.${duplicateOpenOwner.key.second.name.lowercase()}")
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
                    region.onHover.forEach { addAll(it.actions + it.deny) }
                    region.onLeave.forEach { addAll(it.actions + it.deny) }
                }
            }.flatMap(::leafActions)
            actions.forEach { action ->
                val id = when (action) {
                    is PlayAnimationAction -> action.animationId
                    is StopAnimationAction -> action.animationId
                    else -> return@forEach
                }
                if ('%' !in id && '{' !in id && id !in available) {
                    throw MenuFormatException("$sourceName.menus.$menuId: Action references an unbound animation track $id")
                }
            }
        }
        return AnimationConfiguration(bindings)
    }

    private fun identifier(id: String, reader: Reader) {
        if (!Regex("[a-z0-9][a-z0-9_-]*").matches(id)) reader.fail("", "ID must contain only lowercase letters, digits, underscores, or hyphens")
    }

    private fun visualNodes(nodes: List<VisualNode>): Map<String, VisualNode> = buildMap {
        fun visit(node: VisualNode) {
            put(node.properties.id, node)
            if (node is GroupNode) node.children.forEach(::visit)
        }
        nodes.forEach(::visit)
    }

    private class Reader(value: Any?, val path: String) {
        private val values: Map<String, Any?> = (value as? Map<*, *>)?.let { map ->
            if (map.keys.any { it !is String }) fail("", "Object keys must be strings")
            @Suppress("UNCHECKED_CAST") (map as Map<String, Any?>)
        } ?: fail("", "Must be a YAML object")

        fun fail(key: String, message: String): Nothing = throw MenuFormatException("$path${if (key.isBlank()) "" else ".$key"}: $message")
        fun only(vararg allowed: String) = values.keys.firstOrNull { it !in allowed }?.let { fail(it, "Unsupported field") }
        fun has(key: String) = key in values
        fun obj(key: String) = Reader(if (has(key)) values[key] else emptyMap<String, Any?>(), "$path.$key")
        fun entries() = values.map { (key, value) -> key to Reader(value, "$path.$key") }
        fun list(key: String): List<Any?> = if (!has(key)) emptyList() else values[key] as? List<*> ?: fail(key, "Must be a list")
        fun strings(key: String): List<String> {
            val list = list(key)
            return list.mapIndexed { index, value -> value as? String ?: fail("$key[$index]", "Must be a string") }
        }
        fun string(key: String, default: String? = null): String =
            if (!has(key) && default != null) default else values[key] as? String ?: fail(key, "Must be a string")
        fun number(key: String, default: Double? = null): Double {
            if (!has(key) && default != null) return default
            return (values[key] as? Number)?.toDouble()?.takeIf(Double::isFinite) ?: fail(key, "Must be a finite number")
        }
        fun int(key: String, default: Int? = null): Int {
            val value = number(key, default?.toDouble())
            if (value % 1.0 != 0.0 || value !in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) fail(key, "Must be an integer")
            return value.toInt()
        }
        fun positiveInt(key: String): Int = int(key).also { if (it !in 1..72_000) fail(key, "Must be between 1 and 72000 ticks") }
        fun easing(): Easing = when (string("easing", "linear").lowercase().replace('_', '-')) {
            "linear" -> Easing.LINEAR
            "ease-in" -> Easing.EASE_IN
            "ease-out" -> Easing.EASE_OUT
            "ease-in-out" -> Easing.EASE_IN_OUT
            else -> fail("easing", "Must be linear, ease-in, ease-out, or ease-in-out")
        }
        fun vector(key: String, default: Vec3?): Vec3 {
            if (!has(key) && default != null) return default
            val reader = obj(key)
            reader.only("x", "y", "z")
            val value = Vec3(reader.number("x", 0.0), reader.number("y", 0.0), reader.number("z", 0.0))
            if (listOf(value.x, value.y, value.z).any { abs(it) > 100_000.0 }) reader.fail("", "Absolute component values must not exceed 100000")
            return value
        }
        fun scale(key: String, defaultX: Double?, defaultY: Double?, allowZ: Boolean = false): ScaleFrameValue {
            if (!has(key) && defaultX != null && defaultY != null) return ScaleFrameValue(defaultX, defaultY)
            val reader = obj(key)
            if (allowZ) reader.only("x", "y", "z") else reader.only("x", "y")
            val x = reader.number("x", defaultX)
            val y = reader.number("y", defaultY)
            val z = if (allowZ && reader.has("z")) reader.number("z") else null
            if (listOfNotNull(x, y, z).any { it == 0.0 || abs(it) > 1000.0 }) reader.fail("", "Scale must be nonzero with an absolute value no greater than 1000")
            return ScaleFrameValue(x, y, z)
        }
    }
}
