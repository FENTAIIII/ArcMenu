package com.fentai.arcmenu.core.config

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.NodeTuple
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag
import java.io.StringWriter

/** Completes only item/block depth, keeping YAML comments and shared aliases semantically intact. */
object ModelScaleDefaults {
    fun complete(source: String): String {
        val options = LoaderOptions().apply { isAllowDuplicateKeys = false; isProcessComments = true }
        val root = Yaml(SafeConstructor(options)).compose(source.reader()) as? MappingNode ?: return source
        var changed = false

        fun scalar(value: String, tag: Tag = Tag.STR) =
            ScalarNode(tag, value, null, null, DumperOptions.ScalarStyle.PLAIN)

        fun fields(node: MappingNode): Map<String, Node> {
            val values = linkedMapOf<String, Node>()
            node.value.filter { it.keyNode.tag == Tag.MERGE }.forEach { tuple ->
                val sources = when (val value = tuple.valueNode) {
                    is MappingNode -> listOf(value)
                    is SequenceNode -> value.value.filterIsInstance<MappingNode>()
                    else -> emptyList()
                }
                sources.forEach { parent -> fields(parent).forEach { (key, value) -> values.putIfAbsent(key, value) } }
            }
            node.value.filter { it.keyNode.tag != Tag.MERGE }.forEach { tuple ->
                (tuple.keyNode as? ScalarNode)?.value?.let { values[it] = tuple.valueNode }
            }
            return values
        }

        fun replace(node: MappingNode, key: String, value: Node): MappingNode {
            var found = false
            val entries = node.value.map { tuple ->
                if ((tuple.keyNode as? ScalarNode)?.value == key) {
                    found = true
                    NodeTuple(tuple.keyNode, value)
                } else tuple
            }.toMutableList()
            if (!found) entries += NodeTuple(scalar(key), value)
            return MappingNode(node.tag, entries, node.flowStyle).also {
                it.blockComments = node.blockComments
                it.inLineComments = node.inLineComments
                it.endComments = node.endComments
            }
        }

        fun visuals(nodes: MappingNode): MappingNode {
            var result = nodes
            fields(nodes).forEach { (id, value) ->
                val node = value as? MappingNode ?: return@forEach
                val properties = fields(node)
                val type = (properties["type"] as? ScalarNode)?.value
                val next = when (type) {
                    "item", "block" -> {
                        val existing = properties["scale"]
                        val scale = existing as? MappingNode
                        if (existing != null && scale == null || scale != null && fields(scale).containsKey("z")) node
                        else {
                            changed = true
                            val defaults = scale ?: MappingNode(Tag.MAP, emptyList(), DumperOptions.FlowStyle.FLOW)
                            replace(node, "scale", replace(defaults, "z", scalar("1", Tag.INT)))
                        }
                    }
                    "group" -> (properties["children"] as? MappingNode)?.let { children ->
                        val completed = visuals(children)
                        if (completed === children) node else replace(node, "children", completed)
                    } ?: node
                    else -> node
                }
                if (next !== node) result = replace(result, id, next)
            }
            return result
        }

        val frontend = fields(root)["frontend"] as? MappingNode ?: return source
        val completed = visuals(frontend)
        if (!changed) return source
        val dumper = DumperOptions().apply {
            isProcessComments = true
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            lineBreak = if ("\r\n" in source) DumperOptions.LineBreak.WIN else DumperOptions.LineBreak.UNIX
        }
        return StringWriter().also { Yaml(dumper).serialize(replace(root, "frontend", completed), it) }.toString()
    }
}
