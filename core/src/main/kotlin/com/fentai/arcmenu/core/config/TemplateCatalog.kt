package com.fentai.arcmenu.core.config

import com.fentai.arcmenu.core.model.*
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Files
import java.nio.file.Path

data class VisualTemplate(
    val id: String,
    val root: GroupNode,
    val source: String,
    val sourceName: String,
)

data class TemplateLoadResult(val applied: Boolean, val count: Int, val errors: List<String>)

/** Loads group-only templates without introducing a live link into menu documents. */
class TemplateCatalog {
    private val parser = MenuParser()
    var templates: Map<String, VisualTemplate> = emptyMap()
        private set

    fun reload(directory: Path): TemplateLoadResult {
        Files.createDirectories(directory)
        val candidate = linkedMapOf<String, VisualTemplate>()
        val errors = mutableListOf<String>()
        Files.list(directory).use { stream ->
            stream.filter { Files.isRegularFile(it) && Regex("\\.ya?ml$", RegexOption.IGNORE_CASE).containsMatchIn(it.fileName.toString()) }
                .sorted().forEach { file ->
                    try {
                        val template = parse(Files.readString(file), file.fileName.toString())
                        require(candidate.putIfAbsent(template.id, template) == null) { "重复模板 ID: ${template.id}" }
                    } catch (error: Exception) {
                        errors += "${file.fileName}: ${error.message}"
                    }
                }
        }
        if (errors.isNotEmpty()) return TemplateLoadResult(false, templates.size, errors)
        templates = candidate.toMap()
        return TemplateLoadResult(true, templates.size, emptyList())
    }

    fun parse(source: String, sourceName: String = "template.yml"): VisualTemplate {
        val options = LoaderOptions().apply { isAllowDuplicateKeys = false }
        val loaded = try { Yaml(SafeConstructor(options)).load<Any?>(source) } catch (error: Exception) {
            throw MenuFormatException("$sourceName: ${error.message}")
        }
        val map = loaded as? Map<*, *> ?: throw MenuFormatException("$sourceName: 模板必须为 YAML 对象")
        require(map.keys.all { it is String }) { "$sourceName: 模板键必须为字符串" }
        @Suppress("UNCHECKED_CAST") val values = map as Map<String, Any?>
        values.keys.firstOrNull { it !in setOf("schema-version", "id", "frontend") }?.let {
            throw MenuFormatException("$sourceName.$it: 模板不支持该字段")
        }
        val schema = (values["schema-version"] as? Number)?.toInt()
        require(schema == 1) { "$sourceName.schema-version: 仅支持版本 1" }
        val id = values["id"] as? String ?: throw MenuFormatException("$sourceName.id: 必须为字符串")
        require(Regex("[a-z0-9][a-z0-9_-]*").matches(id)) { "$sourceName.id: 使用小写字母、数字、下划线或连字符" }
        val frontend = values["frontend"] as? Map<*, *> ?: throw MenuFormatException("$sourceName.frontend: 必须为对象")
        require(frontend.size == 1) { "$sourceName.frontend: 组模板必须且只能包含一个根节点" }

        val synthetic = linkedMapOf<String, Any?>(
            "schema-version" to 1,
            "id" to "template-$id",
            "frontend" to frontend,
            "backend" to emptyMap<String, Any?>(),
        )
        val dumpOptions = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
        }
        val definition = parser.parse(Yaml(dumpOptions).dump(synthetic), sourceName).definition
        val root = definition.frontend.singleOrNull() as? GroupNode
            ?: throw MenuFormatException("$sourceName.frontend: 模板根节点必须为 type: group")
        return VisualTemplate(id, root, source, sourceName)
    }
}

object TemplateInstantiator {
    /** Returns a detached visual subtree; later edits never mutate or follow the template. */
    fun instantiate(template: VisualTemplate, newRootId: String): GroupNode {
        require(Regex("[a-z0-9][a-z0-9_-]*").matches(newRootId)) { "新组 ID 格式无效" }
        val ids = mutableSetOf<String>()
        fun copy(node: VisualNode, root: Boolean = false): VisualNode {
            val nextId = if (root) newRootId else "$newRootId-${node.properties.id}"
            require(nextId.length <= 128 && ids.add(nextId)) { "模板实例化后 ID 重复或过长: $nextId" }
            val properties = node.properties.copy(id = nextId)
            return when (node) {
                is GroupNode -> node.copy(properties = properties, children = node.children.map { copy(it) })
                is RectangleNode -> node.copy(properties = properties)
                is FrameNode -> node.copy(properties = properties)
                is TextNode -> node.copy(properties = properties)
                is ImageNode -> node.copy(properties = properties)
                is ItemNode -> node.copy(properties = properties)
                is BlockNode -> node.copy(properties = properties)
            }
        }
        return copy(template.root, true) as GroupNode
    }
}
