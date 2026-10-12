package com.fentai.arcmenu.core.config

import com.fentai.arcmenu.core.behavior.*
import com.fentai.arcmenu.core.model.*
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException

class MenuFormatException(message: String) : IllegalArgumentException(message)

/** Strict author-document reader. Business expressions remain untouched for the later dialect compiler. */
class MenuParser {
    fun parse(source: String, sourceName: String = "menu.yml"): MenuDocument {
        val loaded = try {
            val options = LoaderOptions().apply { isAllowDuplicateKeys = false }
            Yaml(SafeConstructor(options)).load<Any?>(source)
        } catch (error: YAMLException) {
            throw MenuFormatException("$sourceName: ${error.message}")
        }
        val root = ObjectReader(loaded, sourceName)
        root.only("schema-version", "id", "permission", "permission-denied-menu", "open-commands", "main-menu", "canvas", "frontend", "backend", "events")
        if (root.int("schema-version") != 1) root.fail("schema-version", "Only version 1 is supported")
        val id = root.string("id")
        if (!Regex("[a-z0-9][a-z0-9_-]*").matches(id)) root.fail("id", "Use lowercase letters, digits, underscores, or hyphens")
        val permissionDeniedMenu = root.string("permission-denied-menu", "")
        if (permissionDeniedMenu.isNotEmpty() && !Regex("[a-z0-9][a-z0-9_-]*").matches(permissionDeniedMenu)) {
            root.fail("permission-denied-menu", "Use a static lowercase menu ID with letters, digits, underscores, or hyphens")
        }
        val openCommands = root.strings("open-commands")
        openCommands.forEachIndexed { index, command ->
            if (command.length > 64 || !Regex("[a-z0-9][a-z0-9_-]*").matches(command)) {
                root.fail("open-commands[$index]", "Use a lowercase command name without /; digits, underscores, and hyphens are allowed")
            }
        }
        if (openCommands.distinct().size != openCommands.size) root.fail("open-commands", "Must not contain duplicate commands")
        val canvas = root.obj("canvas")
        canvas.only("width", "height", "pixels-per-block", "distance")
        val definitionCanvas = Canvas(
            canvas.positive("width", 320.0), canvas.positive("height", 180.0),
            canvas.positive("pixels-per-block", 100.0), canvas.positive("distance", 3.0),
        )
        val ids = mutableSetOf<String>()
        lateinit var structuredAction: (Map<*, *>, ObjectReader, String) -> MenuAction
        fun update(reader: ObjectReader, key: String = "update"): Int {
            val interval = reader.int(key, -1)
            if (interval == 0 || interval < -1) reader.fail(key, "Must be -1 (no periodic refresh) or a positive tick interval")
            return interval
        }
        fun action(value: Any?, reader: ObjectReader, key: String): MenuAction {
            val source = when (value) {
                is String -> value
                is Map<*, *> -> {
                    return structuredAction(value, reader, key)
                }
                else -> reader.fail(key, "Action must be a string or a single-entry action mapping")
            }
            return try { ActionLanguage.parse(source) } catch (error: BehaviorSyntaxException) {
                reader.fail(key, error.message ?: "Invalid action")
            }
        }
        fun actionList(value: Any?, reader: ObjectReader, key: String): List<MenuAction> = when (value) {
            null -> emptyList()
            is String -> listOf(action(value, reader, key))
            is List<*> -> value.mapIndexed { index, item -> action(item, reader, "$key[$index]") }
            is Map<*, *> -> listOf(action(value, reader, key))
            else -> reader.fail(key, "Must be an action string, a single-entry action mapping, or an action list")
        }
        fun condition(value: Any?, reader: ObjectReader, key: String): ConditionExpression {
            val source = value as? String ?: reader.fail(key, "Condition must be a string")
            return try { ConditionLanguage.parse(source) } catch (error: BehaviorSyntaxException) {
                reader.fail(key, error.message ?: "Invalid condition")
            }
        }
        fun stringMap(value: Any?, reader: ObjectReader, key: String): Map<String, Any?> {
            val map = value as? Map<*, *> ?: reader.fail(key, "Must be an object")
            if (map.keys.any { it !is String }) reader.fail(key, "Object keys must be strings")
            @Suppress("UNCHECKED_CAST")
            return map as Map<String, Any?>
        }
        fun intValue(value: Any?, reader: ObjectReader, key: String, fallback: Int): Int {
            if (value == null) return fallback
            val number = (value as? Number)?.toDouble() ?: reader.fail(key, "Must be an integer")
            if (!number.isFinite() || number % 1 != 0.0 || number < Int.MIN_VALUE || number > Int.MAX_VALUE) {
                reader.fail(key, "Must be a valid integer")
            }
            return number.toInt()
        }
        fun conditionalKey(value: Any?): String? {
            val key = (value as? String)?.lowercase()?.replace('_', '-') ?: return null
            return when {
                Regex("(require(ment)?|cond(ition)?)s?").matches(key) -> "condition"
                Regex("pri(ority)?s?").matches(key) -> "priority"
                Regex("(list|action|click|execute|cmd)s?").matches(key) -> "actions"
                Regex("deny-?(list|action|click|execute|cmd)?s?").matches(key) -> "deny"
                else -> null
            }
        }
        fun conditionalMap(value: Any?, reader: ObjectReader, key: String): Map<String, Any?> {
            val original = stringMap(value, reader, key)
            val result = linkedMapOf<String, Any?>()
            for ((rawKey, item) in original) {
                val canonical = conditionalKey(rawKey) ?: reader.fail("$key.$rawKey", "Unsupported conditional action group field")
                if (result.containsKey(canonical)) reader.fail("$key.$rawKey", "Duplicates another alias for $canonical")
                result[canonical] = item
            }
            return result
        }
        var reactionOrder = 0
        fun conditionalReaction(value: Any?, reader: ObjectReader, key: String, defaultPriority: Int): ActionReaction {
            val map = conditionalMap(value, reader, key)
            if (!map.containsKey("actions") && !map.containsKey("deny")) reader.fail(key, "Conditional action group requires actions or deny")
            return ActionReaction(
                intValue(map["priority"], reader, "$key.priority", defaultPriority), reactionOrder++,
                map["condition"]?.let { condition(it, reader, "$key.condition") },
                actionList(map["actions"], reader, "$key.actions"),
                actionList(map["deny"], reader, "$key.deny"),
            )
        }
        fun reactions(value: Any?, reader: ObjectReader, key: String): List<ActionReaction> = when (value) {
            null -> emptyList()
            is String -> listOf(ActionReaction(0, reactionOrder++, actions = listOf(action(value, reader, key))))
            is List<*> -> value.mapIndexed { index, entry ->
                if (entry is Map<*, *> && entry.keys.all { conditionalKey(it) != null }) conditionalReaction(entry, reader, "$key[$index]", index)
                else ActionReaction(index, reactionOrder++, actions = listOf(action(entry, reader, "$key[$index]")))
            }
            is Map<*, *> -> if (value.keys.all { conditionalKey(it) != null }) {
                listOf(conditionalReaction(value, reader, key, 0))
            } else listOf(ActionReaction(0, reactionOrder++, actions = listOf(action(value, reader, key))))
            else -> reader.fail(key, "Must be an action, action list, or conditional action group")
        }
        structuredAction = fun(value: Map<*, *>, reader: ObjectReader, key: String): MenuAction {
            if (value.size != 1 || value.keys.singleOrNull() !is String) reader.fail(key, "Action mapping must have exactly one string action name")
            val entry = value.entries.single()
            val actionName = entry.key.toString()
            val normalized = ActionLanguage.normalizeName(actionName)
            if (normalized in setOf("catcher", "input-catcher")) {
                val stages = stringMap(entry.value, reader, "$key.$actionName")
                if (stages.isEmpty()) reader.fail("$key.$actionName", "catcher requires at least one input stage")
                return CatcherAction(stages.map { (stageId, stageValue) ->
                    val stage = ObjectReader(stageValue, "$key.$actionName.$stageId")
                    stage.only("type", "start", "before", "cancel", "end", "after", "display", "name", "title", "content", "book", "item-left", "item-right")
                    val typeName = stage.string("type", "CHAT")
                    if (!typeName.equals("CHAT", true)) {
                        stage.fail("type", "The current cross-version implementation only supports TrMenu CHAT catcher; SIGN/ANVIL/BOOK require version-specific input adapters")
                    }
                    CatcherStage(
                        stageId, CatcherType.CHAT,
                        reactions(stage.raw(if (stage.has("start")) "start" else "before"), stage, "start"),
                        reactions(stage.raw("cancel"), stage, "cancel"),
                        reactions(stage.raw(if (stage.has("end")) "end" else "after"), stage, "end"),
                    )
                })
            }
            val argument = entry.value
            if (argument is Map<*, *> || argument is List<*>) reader.fail(key, "Action $actionName does not accept nested objects")
            return try { ActionLanguage.parse("$actionName: ${argument ?: ""}") } catch (error: BehaviorSyntaxException) {
                reader.fail(key, error.message ?: "Invalid action")
            }
        }
        fun clickTrigger(key: String, reader: ObjectReader): ClickTrigger = when (key.lowercase().replace('_', '-')) {
            "all", "any" -> ClickTrigger.ALL
            "left" -> ClickTrigger.LEFT
            "right" -> ClickTrigger.RIGHT
            "middle" -> ClickTrigger.MIDDLE
            "shift" -> ClickTrigger.SHIFT
            "shift-left" -> ClickTrigger.SHIFT_LEFT
            "shift-right" -> ClickTrigger.SHIFT_RIGHT
            "shift-middle" -> ClickTrigger.SHIFT_MIDDLE
            else -> reader.fail("actions.$key", "Unsupported M2 click type; use all/left/right/middle/shift/shift-left/shift-right/shift-middle")
        }
        fun clickActions(value: Any?, reader: ObjectReader): ClickActions {
            if (value == null) return ClickActions()
            if (value !is Map<*, *>) return ClickActions(listOf(ClickActionGroup(ClickTrigger.ALL, reactions(value, reader, "actions"))))
            val map = stringMap(value, reader, "actions")
            if (map.keys.all { conditionalKey(it) != null }) {
                return ClickActions(listOf(ClickActionGroup(ClickTrigger.ALL, reactions(value, reader, "actions"))))
            }
            val clickKeys = setOf("all", "any", "left", "right", "middle", "shift", "shift-left", "shift-right", "shift-middle")
            if (!map.keys.all { it.lowercase().replace('_', '-') in clickKeys }) {
                return ClickActions(listOf(ClickActionGroup(ClickTrigger.ALL, reactions(value, reader, "actions"))))
            }
            return ClickActions(map.map { (key, group) ->
                ClickActionGroup(clickTrigger(key, reader), reactions(group, reader, "actions.$key"))
            })
        }
        fun register(key: String, reader: ObjectReader) {
            if (key.isBlank() || key.any { it.isISOControl() || it == '/' || it == '\\' }) reader.fail("", "Element ID must be nonempty and must not contain control characters or path separators")
            if (!ids.add(key)) reader.fail("", "Duplicate element ID: $key (must be unique across frontend, backend, and all groups)")
        }
        fun visual(key: String, reader: ObjectReader): VisualNode {
            register(key, reader)
            val common = arrayOf("type", "offset", "rotation", "scale", "visible")
            val properties = NodeProperties(key, transform(reader, reader.string("type") in setOf("item", "block")), reader.bool("visible", true))
            return when (val type = reader.string("type")) {
                "group" -> {
                    reader.only(*common, "children")
                    GroupNode(properties, reader.obj("children").entries().map { (id, child) -> visual(id, child) })
                }
                "rectangle", "line" -> {
                    val heightKey = if (type == "line") "thickness" else "height"
                    reader.only(*common, "width", heightKey, "color", "opacity")
                    RectangleNode(properties, reader.positive("width"), reader.positive(heightKey), reader.color())
                }
                "frame" -> {
                    reader.only(*common, "width", "height", "thickness", "color", "opacity")
                    val width = reader.positive("width")
                    val height = reader.positive("height")
                    val thickness = reader.positive("thickness")
                    if (thickness * 2 >= minOf(width, height)) reader.fail("thickness", "Must be less than half the width and height")
                    FrameNode(properties, width, height, thickness, reader.color())
                }
                "text" -> {
                    reader.only(*common, "content", "size", "font", "opacity", "line-width", "alignment", "update")
                    val font = reader.string("font", "minecraft:default")
                    if (!Regex("[a-z0-9_.-]+:[a-z0-9_./-]+").matches(font)) reader.fail("font", "Must use namespace:path")
                    val alignment = reader.string("alignment", "center")
                    if (alignment !in setOf("left", "center", "right")) reader.fail("alignment", "Must be left, center, or right")
                    val lineWidth = reader.int("line-width", 200)
                    if (lineWidth <= 0) reader.fail("line-width", "Must be greater than 0")
                    TextNode(properties, reader.string("content"), reader.positive("size", 10.0), font, reader.opacity(), lineWidth, alignment, update(reader))
                }
                "image", "gif" -> {
                    val animated = type == "gif"
                    val imageFields = arrayOf("source", "path", "image", "material", "width", "height", "opacity", "update", "color")
                    if (animated) reader.only(*common, *imageFields, "speed") else reader.only(*common, *imageFields)
                    val sourceKeys = listOf("source", "path", "image", "material").filter(reader::has)
                    if (sourceKeys.size != 1) reader.fail("source", "Image must specify exactly one source (aliases: path/image/material)")
                    val source = reader.string(sourceKeys.single())
                    val dynamic = '%' in source
                    val extension = if (animated) "gif" else "png"
                    val pathPattern = if (dynamic) Regex("/[A-Za-z0-9_%:./-]+\\.$extension") else Regex("/[a-z0-9_./-]+\\.$extension")
                    if (!pathPattern.matches(source) || source.contains("//") ||
                        source.split('/').any { it == "." || it == ".." } || source.count { it == '%' } % 2 != 0) {
                        reader.fail(sourceKeys.single(), "Must be a lowercase ${extension.uppercase()} path under the images virtual root, such as /ui/logo.$extension")
                    }
                    ImageNode(
                        properties, source,
                        if (reader.has("width")) reader.positive("width") else null,
                        if (reader.has("height")) reader.positive("height") else null,
                        reader.opacity(), update(reader),
                        if (reader.has("color")) reader.imageColor() else null,
                        animated, if (animated) reader.positive("speed", 1.0) else 1.0,
                    )
                }
                "item" -> {
                    reader.only(*common, "material", "context")
                    val context = reader.string("context", "GUI")
                    if (context !in setOf("GUI", "HEAD", "FIXED", "GROUND", "NONE", "FIRSTPERSON_LEFTHAND", "FIRSTPERSON_RIGHTHAND", "THIRDPERSON_LEFTHAND", "THIRDPERSON_RIGHTHAND")) {
                        reader.fail("context", "Invalid item display context")
                    }
                    ItemNode(properties, reader.string("material"), context)
                }
                "block" -> {
                    reader.only(*common, "block-data")
                    BlockNode(properties, reader.string("block-data"))
                }
                else -> reader.fail("type", "Unknown frontend type: $type")
            }
        }
        val frontend = root.obj("frontend").entries().map { (id, reader) -> visual(id, reader) }
        val backend = root.obj("backend").entries().map { (key, reader) ->
            register(key, reader)
            reader.only("x", "y", "width", "height", "priority", "tooltip", "update", "actions", "condition", "deny", "on-hover", "on-leave")
            InteractionRegion(
                id = key, x = reader.number("x", 0.0), y = reader.number("y", 0.0),
                width = reader.positive("width"), height = reader.positive("height"),
                priority = reader.int("priority", 0), tooltip = reader.strings("tooltip"),
                tooltipUpdateTicks = update(reader),
                condition = reader.raw("condition")?.let { condition(it, reader, "condition") },
                actions = clickActions(reader.raw("actions"), reader),
                deny = actionList(reader.raw("deny"), reader, "deny"),
                onHover = reactions(reader.raw("on-hover"), reader, "on-hover"),
                onLeave = reactions(reader.raw("on-leave"), reader, "on-leave"),
            )
        }
        val eventReader = root.obj("events")
        eventReader.only("open", "close")
        val events = MenuEvents(
            open = reactions(eventReader.raw("open"), eventReader, "open"),
            close = reactions(eventReader.raw("close"), eventReader, "close"),
        )
        return MenuDocument(MenuDefinition(
            id = id,
            canvas = definitionCanvas,
            frontend = frontend,
            backend = backend,
            permission = root.string("permission", ""),
            events = events,
            openCommands = openCommands,
            mainMenu = root.bool("main-menu", false),
            permissionDeniedMenu = permissionDeniedMenu,
        ), source, sourceName)
    }

    private fun transform(reader: ObjectReader, model: Boolean): Transform {
        fun vector(name: String): Vec3 {
            val obj = reader.obj(name)
            obj.only("x", "y", "z")
            return Vec3(obj.number("x", 0.0), obj.number("y", 0.0), obj.number("z", 0.0))
        }
        val scale = reader.obj("scale")
        if (model) scale.only("x", "y", "z") else scale.only("x", "y")
        val x = scale.number("x", 1.0)
        val y = scale.number("y", 1.0)
        val z = if (model) scale.number("z", 1.0) else 1.0
        if (x == 0.0 || y == 0.0 || z == 0.0) scale.fail("", "Static scale must be nonzero; use visible: false to hide a node")
        return Transform(vector("offset"), vector("rotation"), x, y, z)
    }

    private class ObjectReader(value: Any?, private val path: String) {
        private val values: Map<String, Any?> = (value as? Map<*, *>)?.let { map ->
            if (map.keys.any { it !is String }) fail("", "Object keys must be strings (quote special IDs)")
            @Suppress("UNCHECKED_CAST")
            (map as Map<String, Any?>)
        } ?: fail("", "Must be a YAML object")

        fun fail(key: String, message: String): Nothing = throw MenuFormatException("$path${if (key.isEmpty()) "" else ".$key"}: $message")
        fun has(key: String) = values.containsKey(key)
        fun raw(key: String): Any? = values[key]
        fun only(vararg allowed: String) {
            values.keys.firstOrNull { it !in allowed }?.let { fail(it, "Unsupported field; check its spelling and support in the current stage") }
        }
        fun entries() = values.map { (key, value) -> key to ObjectReader(value, "$path.$key") }
        fun obj(key: String) = ObjectReader(if (has(key)) values[key] else emptyMap<String, Any?>(), "$path.$key")
        fun string(key: String, default: String? = null): String =
            if (!has(key) && default != null) default else values[key] as? String ?: fail(key, "Must be a string")
        fun bool(key: String, default: Boolean): Boolean =
            if (!has(key)) default else values[key] as? Boolean ?: fail(key, "Must be true or false")
        fun number(key: String, default: Double? = null): Double {
            if (!has(key) && default != null) return default
            val number = (values[key] as? Number)?.toDouble() ?: fail(key, "Must be a number")
            if (!number.isFinite()) fail(key, "Must be a finite number")
            return number
        }
        fun positive(key: String, default: Double? = null): Double = number(key, default).also {
            if (it <= 0.0) fail(key, "Must be greater than 0")
        }
        fun int(key: String, default: Int? = null): Int {
            val value = number(key, default?.toDouble())
            if (value % 1 != 0.0 || value < Int.MIN_VALUE || value > Int.MAX_VALUE) fail(key, "Must be a valid integer")
            return value.toInt()
        }
        fun opacity(): Int = int("opacity", 255).also { if (it !in 0..255) fail("opacity", "Must be between 0 and 255") }
        fun color(): Int {
            val raw = string("color", "#FFFFFF")
            if (!Regex("#[0-9a-fA-F]{6}").matches(raw)) fail("color", "Must be a quoted #RRGGBB; specify transparency separately with opacity")
            return (opacity() shl 24) or raw.substring(1).toInt(16)
        }
        fun imageColor(): Int {
            val raw = string("color").removePrefix("&")
            if (!Regex("#[0-9a-fA-F]{6}").matches(raw)) fail("color", "Must be a quoted #RRGGBB or &#RRGGBB; specify transparency separately with opacity")
            return raw.substring(1).toInt(16)
        }
        fun strings(key: String): List<String> {
            if (!has(key)) return emptyList()
            val value = values[key]
            if (value is String) return listOf(value)
            val list = value as? List<*> ?: fail(key, "Must be a string or a list of strings")
            return list.mapIndexed { index, item -> item as? String ?: fail("$key[$index]", "Must be a string") }
        }
    }
}
