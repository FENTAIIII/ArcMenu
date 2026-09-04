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
        root.only("schema-version", "id", "permission", "open-commands", "main-menu", "canvas", "frontend", "backend", "events")
        if (root.int("schema-version") != 1) root.fail("schema-version", "仅支持版本 1")
        val id = root.string("id")
        if (!Regex("[a-z0-9][a-z0-9_-]*").matches(id)) root.fail("id", "使用小写字母、数字、下划线或连字符")
        val openCommands = root.strings("open-commands")
        openCommands.forEachIndexed { index, command ->
            if (command.length > 64 || !Regex("[a-z0-9][a-z0-9_-]*").matches(command)) {
                root.fail("open-commands[$index]", "只能填写不带 / 的小写命令名，可使用数字、下划线或连字符")
            }
        }
        if (openCommands.distinct().size != openCommands.size) root.fail("open-commands", "不能包含重复命令")
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
            if (interval == 0 || interval < -1) reader.fail(key, "必须为 -1（不周期刷新）或大于 0 的 tick 间隔")
            return interval
        }
        fun action(value: Any?, reader: ObjectReader, key: String): MenuAction {
            val source = when (value) {
                is String -> value
                is Map<*, *> -> {
                    return structuredAction(value, reader, key)
                }
                else -> reader.fail(key, "动作必须为字符串或单项动作映射")
            }
            return try { ActionLanguage.parse(source) } catch (error: BehaviorSyntaxException) {
                reader.fail(key, error.message ?: "无效动作")
            }
        }
        fun actionList(value: Any?, reader: ObjectReader, key: String): List<MenuAction> = when (value) {
            null -> emptyList()
            is String -> listOf(action(value, reader, key))
            is List<*> -> value.mapIndexed { index, item -> action(item, reader, "$key[$index]") }
            is Map<*, *> -> listOf(action(value, reader, key))
            else -> reader.fail(key, "必须为动作字符串、单项动作映射或动作列表")
        }
        fun condition(value: Any?, reader: ObjectReader, key: String): ConditionExpression {
            val source = value as? String ?: reader.fail(key, "条件必须为字符串")
            return try { ConditionLanguage.parse(source) } catch (error: BehaviorSyntaxException) {
                reader.fail(key, error.message ?: "无效条件")
            }
        }
        fun stringMap(value: Any?, reader: ObjectReader, key: String): Map<String, Any?> {
            val map = value as? Map<*, *> ?: reader.fail(key, "必须为对象")
            if (map.keys.any { it !is String }) reader.fail(key, "对象键必须为字符串")
            @Suppress("UNCHECKED_CAST")
            return map as Map<String, Any?>
        }
        fun intValue(value: Any?, reader: ObjectReader, key: String, fallback: Int): Int {
            if (value == null) return fallback
            val number = (value as? Number)?.toDouble() ?: reader.fail(key, "必须为整数")
            if (!number.isFinite() || number % 1 != 0.0 || number < Int.MIN_VALUE || number > Int.MAX_VALUE) {
                reader.fail(key, "必须为有效整数")
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
                val canonical = conditionalKey(rawKey) ?: reader.fail("$key.$rawKey", "条件动作组不支持该字段")
                if (result.containsKey(canonical)) reader.fail("$key.$rawKey", "与另一个 $canonical 别名重复")
                result[canonical] = item
            }
            return result
        }
        var reactionOrder = 0
        fun conditionalReaction(value: Any?, reader: ObjectReader, key: String, defaultPriority: Int): ActionReaction {
            val map = conditionalMap(value, reader, key)
            if (!map.containsKey("actions") && !map.containsKey("deny")) reader.fail(key, "条件动作组至少需要 actions 或 deny")
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
            else -> reader.fail(key, "必须为动作、动作列表或条件动作组")
        }
        structuredAction = fun(value: Map<*, *>, reader: ObjectReader, key: String): MenuAction {
            if (value.size != 1 || value.keys.singleOrNull() !is String) reader.fail(key, "映射动作必须只有一个字符串动作名")
            val entry = value.entries.single()
            val actionName = entry.key.toString()
            val normalized = ActionLanguage.normalizeName(actionName)
            if (normalized in setOf("catcher", "input-catcher")) {
                val stages = stringMap(entry.value, reader, "$key.$actionName")
                if (stages.isEmpty()) reader.fail("$key.$actionName", "catcher 至少需要一个输入阶段")
                return CatcherAction(stages.map { (stageId, stageValue) ->
                    val stage = ObjectReader(stageValue, "$key.$actionName.$stageId")
                    stage.only("type", "start", "before", "cancel", "end", "after", "display", "name", "title", "content", "book", "item-left", "item-right")
                    val typeName = stage.string("type", "CHAT")
                    if (!typeName.equals("CHAT", true)) {
                        stage.fail("type", "当前跨版本实现只支持 TrMenu CHAT catcher；SIGN/ANVIL/BOOK 需要版本输入适配器")
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
            if (argument is Map<*, *> || argument is List<*>) reader.fail(key, "动作 $actionName 不接受嵌套对象")
            return try { ActionLanguage.parse("$actionName: ${argument ?: ""}") } catch (error: BehaviorSyntaxException) {
                reader.fail(key, error.message ?: "无效动作")
            }
        }
        fun clickTrigger(key: String, reader: ObjectReader): ClickTrigger = when (key.lowercase().replace('_', '-')) {
            "all", "any" -> ClickTrigger.ALL
            "left" -> ClickTrigger.LEFT
            "right" -> ClickTrigger.RIGHT
            "shift" -> ClickTrigger.SHIFT
            "shift-left" -> ClickTrigger.SHIFT_LEFT
            "shift-right" -> ClickTrigger.SHIFT_RIGHT
            else -> reader.fail("actions.$key", "M2 不支持点击类型；可用 all/left/right/shift/shift-left/shift-right")
        }
        fun clickActions(value: Any?, reader: ObjectReader): ClickActions {
            if (value == null) return ClickActions()
            if (value !is Map<*, *>) return ClickActions(listOf(ClickActionGroup(ClickTrigger.ALL, reactions(value, reader, "actions"))))
            val map = stringMap(value, reader, "actions")
            if (map.keys.all { conditionalKey(it) != null }) {
                return ClickActions(listOf(ClickActionGroup(ClickTrigger.ALL, reactions(value, reader, "actions"))))
            }
            val clickKeys = setOf("all", "any", "left", "right", "shift", "shift-left", "shift-right")
            if (!map.keys.all { it.lowercase().replace('_', '-') in clickKeys }) {
                return ClickActions(listOf(ClickActionGroup(ClickTrigger.ALL, reactions(value, reader, "actions"))))
            }
            return ClickActions(map.map { (key, group) ->
                ClickActionGroup(clickTrigger(key, reader), reactions(group, reader, "actions.$key"))
            })
        }
        fun register(key: String, reader: ObjectReader) {
            if (key.isBlank() || key.any { it.isISOControl() || it == '/' || it == '\\' }) reader.fail("", "元素 ID 不能为空或包含控制字符、路径分隔符")
            if (!ids.add(key)) reader.fail("", "重复元素 ID: $key（前后端及所有组内必须唯一）")
        }
        fun visual(key: String, reader: ObjectReader): VisualNode {
            register(key, reader)
            val common = arrayOf("type", "offset", "rotation", "scale", "visible")
            val properties = NodeProperties(key, transform(reader), reader.bool("visible", true))
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
                    if (thickness * 2 >= minOf(width, height)) reader.fail("thickness", "必须小于宽和高的一半")
                    FrameNode(properties, width, height, thickness, reader.color())
                }
                "text" -> {
                    reader.only(*common, "content", "size", "font", "opacity", "line-width", "alignment", "update")
                    val font = reader.string("font", "minecraft:default")
                    if (!Regex("[a-z0-9_.-]+:[a-z0-9_./-]+").matches(font)) reader.fail("font", "必须为 namespace:path")
                    val alignment = reader.string("alignment", "center")
                    if (alignment !in setOf("left", "center", "right")) reader.fail("alignment", "必须为 left、center 或 right")
                    val lineWidth = reader.int("line-width", 200)
                    if (lineWidth <= 0) reader.fail("line-width", "必须大于 0")
                    TextNode(properties, reader.string("content"), reader.positive("size", 10.0), font, reader.opacity(), lineWidth, alignment, update(reader))
                }
                "image" -> {
                    reader.only(*common, "source", "path", "image", "material", "width", "height", "opacity", "update")
                    val sourceKeys = listOf("source", "path", "image", "material").filter(reader::has)
                    if (sourceKeys.size != 1) reader.fail("source", "图片必须且只能填写 source（兼容 path/image/material）中的一个")
                    val source = reader.string(sourceKeys.single())
                    val dynamic = '%' in source
                    val pathPattern = if (dynamic) Regex("/[A-Za-z0-9_%:./-]+\\.png") else Regex("/[a-z0-9_./-]+\\.png")
                    if (!pathPattern.matches(source) || source.contains("//") ||
                        source.split('/').any { it == "." || it == ".." } || source.count { it == '%' } % 2 != 0) {
                        reader.fail(sourceKeys.single(), "必须为 images 虚拟根下的小写 PNG 路径，例如 /ui/logo.png")
                    }
                    ImageNode(
                        properties, source,
                        if (reader.has("width")) reader.positive("width") else null,
                        if (reader.has("height")) reader.positive("height") else null,
                        reader.opacity(), update(reader),
                    )
                }
                "item" -> {
                    reader.only(*common, "material", "context")
                    val context = reader.string("context", "GUI")
                    if (context !in setOf("GUI", "HEAD", "FIXED", "GROUND", "NONE", "FIRSTPERSON_LEFTHAND", "FIRSTPERSON_RIGHTHAND", "THIRDPERSON_LEFTHAND", "THIRDPERSON_RIGHTHAND")) {
                        reader.fail("context", "无效的物品展示上下文")
                    }
                    ItemNode(properties, reader.string("material"), context)
                }
                "block" -> {
                    reader.only(*common, "block-data")
                    BlockNode(properties, reader.string("block-data"))
                }
                else -> reader.fail("type", "未知前端类型: $type")
            }
        }
        val frontend = root.obj("frontend").entries().map { (id, reader) -> visual(id, reader) }
        val backend = root.obj("backend").entries().map { (key, reader) ->
            register(key, reader)
            reader.only("x", "y", "width", "height", "priority", "tooltip", "update", "actions", "condition", "deny")
            InteractionRegion(
                id = key, x = reader.number("x", 0.0), y = reader.number("y", 0.0),
                width = reader.positive("width"), height = reader.positive("height"),
                priority = reader.int("priority", 0), tooltip = reader.strings("tooltip"),
                tooltipUpdateTicks = update(reader),
                condition = reader.raw("condition")?.let { condition(it, reader, "condition") },
                actions = clickActions(reader.raw("actions"), reader),
                deny = actionList(reader.raw("deny"), reader, "deny"),
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
        ), source, sourceName)
    }

    private fun transform(reader: ObjectReader): Transform {
        fun vector(name: String): Vec3 {
            val obj = reader.obj(name)
            obj.only("x", "y", "z")
            return Vec3(obj.number("x", 0.0), obj.number("y", 0.0), obj.number("z", 0.0))
        }
        val scale = reader.obj("scale")
        scale.only("x", "y")
        val x = scale.number("x", 1.0)
        val y = scale.number("y", 1.0)
        if (x == 0.0 || y == 0.0) scale.fail("", "静态缩放不能为 0；隐藏节点请使用 visible: false")
        return Transform(vector("offset"), vector("rotation"), x, y)
    }

    private class ObjectReader(value: Any?, private val path: String) {
        private val values: Map<String, Any?> = (value as? Map<*, *>)?.let { map ->
            if (map.keys.any { it !is String }) fail("", "对象键必须是字符串（特殊 ID 请加引号）")
            @Suppress("UNCHECKED_CAST")
            (map as Map<String, Any?>)
        } ?: fail("", "必须为 YAML 对象")

        fun fail(key: String, message: String): Nothing = throw MenuFormatException("$path${if (key.isEmpty()) "" else ".$key"}: $message")
        fun has(key: String) = values.containsKey(key)
        fun raw(key: String): Any? = values[key]
        fun only(vararg allowed: String) {
            values.keys.firstOrNull { it !in allowed }?.let { fail(it, "不支持的字段；请检查拼写或当前阶段能力") }
        }
        fun entries() = values.map { (key, value) -> key to ObjectReader(value, "$path.$key") }
        fun obj(key: String) = ObjectReader(if (has(key)) values[key] else emptyMap<String, Any?>(), "$path.$key")
        fun string(key: String, default: String? = null): String =
            if (!has(key) && default != null) default else values[key] as? String ?: fail(key, "必须为字符串")
        fun bool(key: String, default: Boolean): Boolean =
            if (!has(key)) default else values[key] as? Boolean ?: fail(key, "必须为 true 或 false")
        fun number(key: String, default: Double? = null): Double {
            if (!has(key) && default != null) return default
            val number = (values[key] as? Number)?.toDouble() ?: fail(key, "必须为数字")
            if (!number.isFinite()) fail(key, "必须为有限数值")
            return number
        }
        fun positive(key: String, default: Double? = null): Double = number(key, default).also {
            if (it <= 0.0) fail(key, "必须大于 0")
        }
        fun int(key: String, default: Int? = null): Int {
            val value = number(key, default?.toDouble())
            if (value % 1 != 0.0 || value < Int.MIN_VALUE || value > Int.MAX_VALUE) fail(key, "必须为有效整数")
            return value.toInt()
        }
        fun opacity(): Int = int("opacity", 255).also { if (it !in 0..255) fail("opacity", "必须介于 0 和 255") }
        fun color(): Int {
            val raw = string("color", "#FFFFFF")
            if (!Regex("#[0-9a-fA-F]{6}").matches(raw)) fail("color", "必须为带引号的 #RRGGBB；透明度单独填写 opacity")
            return (opacity() shl 24) or raw.substring(1).toInt(16)
        }
        fun strings(key: String): List<String> {
            if (!has(key)) return emptyList()
            val value = values[key]
            if (value is String) return listOf(value)
            val list = value as? List<*> ?: fail(key, "必须为字符串或字符串列表")
            return list.mapIndexed { index, item -> item as? String ?: fail("$key[$index]", "必须为字符串") }
        }
    }
}
