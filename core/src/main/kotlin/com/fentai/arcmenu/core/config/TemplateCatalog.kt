package com.fentai.arcmenu.core.config

import com.fentai.arcmenu.core.model.*
import com.fentai.arcmenu.core.render.*
import org.joml.Vector3d
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
    @Volatile var templates: Map<String, VisualTemplate> = emptyMap()
        private set

    fun reload(directory: Path, writeDefaults: Boolean = true): TemplateLoadResult {
        Files.createDirectories(directory)
        val candidate = linkedMapOf<String, VisualTemplate>()
        val errors = mutableListOf<String>()
        val completed = linkedMapOf<MenuFileDefaults, String>()
        val root = directory.toRealPath()
        try {
            Files.walk(directory).use { stream ->
                stream.filter { Files.isRegularFile(it) && Regex("\\.ya?ml$", RegexOption.IGNORE_CASE).containsMatchIn(it.fileName.toString()) }
                    .sorted().forEach { file ->
                        val name = directory.relativize(file).toString().replace('\\', '/')
                        try {
                            require(file.toRealPath().startsWith(root)) { "Template path escapes the templates directory" }
                            val original = MenuFileDefaults(file, Files.readString(file))
                            val source = ModelScaleDefaults.complete(original.source)
                            val template = parse(source, name)
                            require(candidate.putIfAbsent(template.id, template) == null) { "Duplicate template ID: ${template.id}" }
                            completed[original] = source
                        } catch (error: Exception) {
                            errors += "$name: ${error.message}"
                        }
                    }
            }
        } catch (error: Exception) {
            errors += "Cannot read template directory $directory: ${error.message}"
        }
        if (errors.isNotEmpty()) return TemplateLoadResult(false, templates.size, errors)
        if (writeDefaults) for ((file, source) in completed) try {
            file.write(source)
        } catch (error: Exception) {
            return TemplateLoadResult(false, templates.size, listOf("${file.path.fileName}: Cannot populate template file defaults: ${error.message}"))
        }
        templates = candidate.toMap()
        return TemplateLoadResult(true, templates.size, emptyList())
    }

    fun parse(source: String, sourceName: String = "template.yml"): VisualTemplate {
        val options = LoaderOptions().apply { isAllowDuplicateKeys = false }
        val loaded = try { Yaml(SafeConstructor(options)).load<Any?>(source) } catch (error: Exception) {
            throw MenuFormatException("$sourceName: ${error.message}")
        }
        val map = loaded as? Map<*, *> ?: throw MenuFormatException("$sourceName: Template must be a YAML object")
        require(map.keys.all { it is String }) { "$sourceName: Template keys must be strings" }
        @Suppress("UNCHECKED_CAST") val values = map as Map<String, Any?>
        values.keys.firstOrNull { it !in setOf("schema-version", "id", "frontend") }?.let {
            throw MenuFormatException("$sourceName.$it: Unsupported template field")
        }
        val schema = (values["schema-version"] as? Number)?.toInt()
        require(schema == 1) { "$sourceName.schema-version: Only version 1 is supported" }
        val id = values["id"] as? String ?: throw MenuFormatException("$sourceName.id: must be a string")
        require(Regex("[a-z0-9][a-z0-9_-]*").matches(id)) { "$sourceName.id: Use lowercase letters, digits, underscores, or hyphens" }
        val frontend = values["frontend"] as? Map<*, *> ?: throw MenuFormatException("$sourceName.frontend: must be an object")
        require(frontend.size == 1) { "$sourceName.frontend: Group template must contain exactly one root node" }

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
            ?: throw MenuFormatException("$sourceName.frontend: Template root node must have type: group")
        return VisualTemplate(id, root, source, sourceName)
    }
}

/** Centers a detached preview while preserving every authored template node and its ID. */
fun templateScene(template: VisualTemplate): MenuDefinition = templateScene(template) { null }

/** Missing image dimensions use the original PNG dimensions, matching menu rendering. */
fun templateScene(template: VisualTemplate, imageSize: (String) -> Pair<Double, Double>?): MenuDefinition {
    val canvas = Canvas()
    val points = SceneCompiler().compile(listOf(template.root)).flatMap { primitive ->
        val (width, height) = when (primitive) {
            is Quad -> primitive.width to primitive.height
            is Text -> {
                val lines = primitive.node.content.split('\n')
                maxOf(12.0, lines.maxOf { it.length } * primitive.node.size * 0.5) to
                    maxOf(6.0, lines.size * primitive.node.size)
            }
            is Image -> {
                val intrinsic = if (primitive.node.width == null || primitive.node.height == null) imageSize(primitive.node.source) else null
                (primitive.node.width ?: intrinsic?.first ?: 32.0) to (primitive.node.height ?: intrinsic?.second ?: 32.0)
            }
            is Item, is Block -> 1.0 to 1.0
        }
        listOf(-1.0 to -1.0, -1.0 to 1.0, 1.0 to -1.0, 1.0 to 1.0).map { (x, y) ->
            primitive.transform.transformPosition(Vector3d(x * width / 2, y * height / 2, 0.0))
        }
    }
    if (points.isEmpty()) return MenuDefinition(template.id, canvas, listOf(template.root), emptyList())
    val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
    val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
    val scale = minOf(1.0, (canvas.width - 32) / maxOf(1.0, maxX - minX),
        (canvas.height - 32) / maxOf(1.0, maxY - minY))
    val ids = mutableSetOf<String>()
    fun visit(node: VisualNode) { ids += node.properties.id; if (node is GroupNode) node.children.forEach(::visit) }
    visit(template.root)
    var wrapperId = "template-preview"
    var suffix = 0
    while (wrapperId in ids) wrapperId = "template-preview-${++suffix}"
    val transform = Transform(offset = Vec3(-(minX + maxX) / 2 * scale, -(minY + maxY) / 2 * scale),
        scaleX = scale, scaleY = scale, scaleZ = scale)
    val preview = GroupNode(NodeProperties(wrapperId, transform), listOf(template.root))
    return MenuDefinition(template.id, canvas, listOf(preview), emptyList())
}

object TemplateInstantiator {
    /** Returns a detached visual subtree; later edits never mutate or follow the template. */
    fun instantiate(template: VisualTemplate, newRootId: String): GroupNode {
        require(Regex("[a-z0-9][a-z0-9_-]*").matches(newRootId)) { "Invalid new group ID" }
        val ids = mutableSetOf<String>()
        fun copy(node: VisualNode, root: Boolean = false): VisualNode {
            val nextId = if (root) newRootId else "$newRootId-${node.properties.id}"
            require(nextId.length <= 128 && ids.add(nextId)) { "Instantiated template ID is duplicated or too long: $nextId" }
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
