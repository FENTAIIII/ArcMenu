package com.fentai.arcmenu.paper.pack

import com.fentai.arcmenu.core.config.AnimationCatalog
import com.fentai.arcmenu.core.model.MenuDocument

internal data class ArcPackAnimationBundle(val source: String, val tracks: Map<String, Set<String>>)

/** Only selected bindings and the definitions they reference leave the host server. */
internal object ArcPackAnimations {
    fun collect(sources: List<Pair<String, String>>, selected: Map<String, MenuDocument>): ArcPackAnimationBundle? {
        val transitions = linkedMapOf<String, Any?>(); val tracks = linkedMapOf<String, Any?>(); val bindings = linkedMapOf<String, Any?>()
        fun definition(destination: MutableMap<String, Any?>, source: Map<*, *>, id: String, kind: String) {
            require(source.containsKey(id)) { "Package binding references a missing $kind: $id" }
            val value = source[id]
            require(!destination.containsKey(id) || destination[id] == value) { "Selected projects have different $kind definitions named $id; export these projects separately" }
            destination[id] = value
        }
        sources.forEach { (name, source) ->
            val root = ArcPackSources.read(source)
            val menus = root["menus"] as? Map<*, *> ?: emptyMap<String, Any?>()
            val selectedBindings = menus.filterKeys { it in selected }
            if (selectedBindings.isEmpty()) return@forEach
            // Validate the complete fields and every selected target before reducing the configuration.
            AnimationCatalog().parse(ArcPackSources.write(root.toMutableMap().apply { put("menus", selectedBindings) }), selected.filterKeys { it in selectedBindings }, name)
            selectedBindings.forEach { (key, value) ->
                val id = key as String
                require(id !in bindings) { "Menu $id has more than one source animation binding" }
                val binding = value as? Map<*, *> ?: error("Invalid package animation binding")
                (binding["transition"] as? String)?.let { transition -> definition(transitions, root["transitions"] as? Map<*, *> ?: emptyMap<String, Any?>(), transition, "transition") }
                ((binding["tracks"] as? List<*>) ?: emptyList<Any?>()).forEach { track ->
                    require(track is String) { "Animation track IDs must be strings" }
                    definition(tracks, root["tracks"] as? Map<*, *> ?: emptyMap<String, Any?>(), track, "animation")
                }
                bindings[id] = value
            }
        }
        if (bindings.isEmpty()) return null
        val source = ArcPackSources.write(linkedMapOf("schema-version" to 1, "transitions" to transitions, "tracks" to tracks, "menus" to bindings))
        require(source.toByteArray(Charsets.UTF_8).size <= 2_000_000) { "Exported animation configuration exceeds 2 MB" }
        val parsed = AnimationCatalog().parse(source, selected.filterKeys { it in bindings }, "config/animations.yml")
        return ArcPackAnimationBundle(source, parsed.menus.mapValues { it.value.tracks.keys })
    }
}
