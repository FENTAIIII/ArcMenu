package com.fentai.arcmenu.paper.pack

import com.fentai.arcmenu.core.config.strictYaml

import com.fentai.arcmenu.core.behavior.*
import com.fentai.arcmenu.core.config.VisualTemplate
import com.fentai.arcmenu.core.model.*
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

internal class ArcPackReferences(private val selectedMenus: Set<String>, private val allowCe: Boolean, private val bundledAnimations: Map<String, Set<String>> = emptyMap()) {
    val images = linkedSetOf<String>()
    val dependencies = linkedSetOf<String>()
    private var animationScope: Set<String> = emptySet()
    fun dependency(type: String, value: String) {
        val clean = value.filterNot(Char::isISOControl).take(1900)
        if (clean.isNotBlank()) dependencies += "$type:$clean"
    }
    private fun dynamic(value: String): Boolean = '%' in value || '{' in value
    private fun value(value: String) { if (dynamic(value)) dependency("placeholder", value) }
    private fun font(value: String) { if (!value.startsWith("minecraft:")) dependency("font", value) }
    private fun material(value: String) {
        this.value(value)
        if (dynamic(value)) return
        val base = value.substringBefore('<').trim()
        if (':' in base && !base.startsWith("minecraft:", true)) {
            require(allowCe) { "CraftEngine references require allowCe=true: $base" }
            dependency("craftengine", base)
        }
        if ("<head:" in value || "<skull:" in value) dependency("player-skin", value)
        if ("<model-data:" in value) dependency("resource-pack-model", value)
    }
    private fun block(source: String) {
        value(source)
        val type = source.substringBefore('[')
        if (!dynamic(source) && ':' in type && !type.startsWith("minecraft:")) {
            require(allowCe) { "CraftEngine references require allowCe=true: $type" }
            dependency("craftengine", type)
        }
    }
    fun image(source: String) {
        if (dynamic(source)) { dependency("dynamic-image", source); return }
        require(source.startsWith('/') && (source.endsWith(".png") || source.endsWith(".gif"))) { "Invalid package image reference: $source" }
        images += source.removePrefix("/")
    }
    private fun sound(source: String) {
        require(!source.startsWith("arcmenu:effect/") && !source.endsWith(".nbs") && !source.endsWith(".ogg")) {
            "Effect audio is not supported by ArcMenu Community"
        }
        dependency("sound", source)
    }

    fun scene(scene: MenuDefinition, includeBindings: Boolean = true) {
        val oldScope = animationScope
        animationScope = if (includeBindings) bundledAnimations[scene.id].orEmpty() else emptySet()
        if (scene.permissionDeniedMenu.isNotEmpty() && scene.permissionDeniedMenu !in selectedMenus) {
            dependency("menu", scene.permissionDeniedMenu)
        }
        fun node(node: VisualNode) {
            when (node) {
                is GroupNode -> node.children.forEach(::node)
                is ImageNode -> image(node.source)
                is TextNode -> { value(node.content); font(node.font) }
                is ItemNode -> material(node.material)
                is BlockNode -> block(node.blockData)
                else -> Unit
            }
        }
        scene.frontend.forEach(::node)
        scene.backend.forEach { region ->
            region.actions.allActions().forEach(::action)
            region.deny.forEach(::action)
            region.onHover.forEach(::reaction)
            region.onLeave.forEach(::reaction)
            region.tooltip.forEach(::value)
        }
        (scene.events.open + scene.events.close).forEach(::reaction)
        animationScope = oldScope
    }
    fun template(template: VisualTemplate) = scene(
        MenuDefinition(template.id, Canvas(), listOf(template.root), emptyList()), includeBindings = false)
    private fun reaction(r: ActionReaction) { r.actions.forEach(::action); r.deny.forEach(::action) }
    fun action(a: MenuAction) {
        when (a) {
            is ConfiguredAction -> action(a.action)
            is ActionLanguage.SequenceAction -> a.actions.forEach(::action)
            is CatcherAction -> a.stages.forEach { (it.start + it.cancel + it.end).forEach(::reaction) }
            is OpenMenuAction -> if (a.menuId !in selectedMenus) dependency("menu", a.menuId)
            is OpenApplicationAction -> dependency("application", a.applicationId)
            is PlayAnimationAction -> if (a.animationId !in animationScope) dependency("animation", a.animationId)
            is StopAnimationAction -> if (a.animationId !in animationScope) dependency("animation", a.animationId)
            is SoundAction -> a.specification.split(';').forEach { sound(it.trim().substringBefore(' ').substringBefore(',')) }
            is PlayerCommandAction -> dependency("command", a.command)
            is ConsoleCommandAction -> dependency("console-command", a.command)
            is OpCommandAction -> dependency("op-command", a.command)
            is GiveItemAction -> dependency("item-action", a.specification)
            is TakeItemAction -> dependency("item-action", a.specification)
            is BalanceAction -> dependency("economy", a.kind.name.lowercase())
            else -> Unit
        }
    }
}

internal object ArcPackSources {
    fun read(source: String): MutableMap<String, Any?> {
        val value = strictYaml().load<Any?>(source)
        require(value is Map<*, *> && value.keys.all { it is String }) { "Package project must be a YAML object" }
        @Suppress("UNCHECKED_CAST") return deepCopy(value) as MutableMap<String, Any?>
    }
    private fun deepCopy(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associateTo(linkedMapOf()) { it.key.toString() to deepCopy(it.value) }
        is List<*> -> value.mapTo(mutableListOf(), ::deepCopy)
        else -> value
    }
    fun write(value: Map<String, Any?>): String = Yaml(DumperOptions().apply { defaultFlowStyle = DumperOptions.FlowStyle.BLOCK; indent = 2; isPrettyFlow = true }).dump(value)
    fun scene(source: String, namespace: String, included: Set<String>): String {
        val root = read(source)
        fun visit(value: Any?) {
            when (value) {
                is MutableMap<*, *> -> {
                    @Suppress("UNCHECKED_CAST") val node = value as MutableMap<String, Any?>
                    if (node["type"] in setOf("image", "gif")) {
                        listOf("source", "path", "image", "material").filter(node::containsKey).forEach { key ->
                            val ref = node[key] as? String ?: error("Invalid package image reference")
                            if ('%' !in ref && '{' !in ref) {
                                val file = "images/" + ref.removePrefix("/")
                                require(file in included) { "Package image is missing: $ref" }
                                node[key] = "/$namespace/" + ref.removePrefix("/")
                            }
                        }
                    }
                    node.keys.toList().forEach { key ->
                        val current = node[key]
                        if (key in setOf("actions", "on-hover", "on-leave", "deny", "open", "close", "start", "before", "cancel", "end", "after")) node[key] = rewriteActions(current, namespace, included)
                        else visit(current)
                    }
                }
                is List<*> -> value.forEach(::visit)
            }
        }
        visit(root["frontend"]); visit(root["backend"]); visit(root["events"])
        return write(root)
    }
    private fun audio(source: String, namespace: String, included: Set<String>): String {
        require(!source.startsWith("arcmenu:effect/") && !source.endsWith(".nbs") && !source.endsWith(".ogg")) {
            "Effect audio is not supported by ArcMenu Community"
        }
        return source
    }

    private fun actionString(source: String, namespace: String, included: Set<String>): String {
        val boundary = Regex(" ?(?:_\\|\\|_|&&&) ?")
        var start = 0; val out = StringBuilder()
        (boundary.findAll(source).toList() + listOf(null)).forEach { match ->
            val end = match?.range?.first ?: source.length
            val part = source.substring(start, end)
            val parsed = runCatching { ActionLanguage.parse(part) }.getOrNull()
            val action = if (parsed is ConfiguredAction) parsed.action else parsed
            out.append(if (action is SoundAction) Regex("arcmenu:effect/[a-z0-9_./-]+").replace(part) { audio(it.value, namespace, included) } else part)
            if (match != null) { out.append(match.value); start = match.range.last + 1 }
        }
        return out.toString()
    }
    private fun rewriteActions(value: Any?, namespace: String, included: Set<String>): Any? = when (value) {
        is String -> actionString(value, namespace, included)
        is List<*> -> value.map { rewriteActions(it, namespace, included) }
        is Map<*, *> -> value.entries.associateTo(linkedMapOf()) { (k, v) ->
            val key = k.toString(); val rewritten = if (key.lowercase().replace("_", "").replace("-", "") in setOf("sound", "sounds", "playsound", "playsounds") && v is String) {
                Regex("arcmenu:effect/[a-z0-9_./-]+").replace(v) { audio(it.value, namespace, included) }
            } else if (key.lowercase() in setOf("condition", "conditions", "requirement", "requirements", "priority", "type", "display", "name", "title", "content", "book", "item-left", "item-right")) v
            else rewriteActions(v, namespace, included)
            key to rewritten
        }
        else -> value
    }
}
