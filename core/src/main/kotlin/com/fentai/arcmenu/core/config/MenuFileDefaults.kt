package com.fentai.arcmenu.core.config

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.Tag
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.yaml.snakeyaml.DumperOptions

/** Inserts defaults into the author text without reserializing comments or existing values. */
internal class MenuFileDefaults(val path: Path, val source: String) {
    private val yaml = Yaml(SafeConstructor(LoaderOptions().apply { isAllowDuplicateKeys = false }))
    private val values = if (source.isBlank()) null else yaml.load<Any?>(source)
    private val fields = values as? Map<*, *>
    private val root by lazy { if (source.isBlank()) null else yaml.compose(source.reader()) }
    private val mapping get() = root as? MappingNode
    private val newline = if ("\r\n" in source) "\r\n" else "\n"
    private val canvas = listOf("canvas:", "  width: 320", "  height: 180", "  pixels-per-block: 42.7", "  distance: 3")

    val existingId: String? = fields?.get("id") as? String

    fun complete(usedIds: MutableSet<String>): String = ModelScaleDefaults.complete(completeMenu(usedIds))

    private fun completeMenu(usedIds: MutableSet<String>): String {
        if (fields?.containsKey("canvas") == true) return source
        val root = root
        val mapping = mapping
        val empty = root == null || (root is ScalarNode && root.tag == Tag.NULL && root.value.isEmpty()) ||
            mapping?.value?.isEmpty() == true
        if (empty) {
            val base = path.fileName.toString().removeSuffix(".yml")
            if (!Regex("[a-z0-9][a-z0-9_-]*").matches(base)) {
                throw MenuFormatException("Empty menu filename must follow menu ID rules: use lowercase letters, digits, underscores, or hyphens, starting with a letter or digit")
            }
            var id = base
            var suffix = 1
            while (!usedIds.add(id)) id = "$base${suffix++}"
            // Quote IDs so filenames such as 123.yml and true.yml stay YAML strings.
            val defaults = (listOf("schema-version: 1", "id: '$id'") + canvas).joinToString(newline) + newline
            if (source.isBlank()) return defaults
            val start = root?.startMark?.index?.let(::offset) ?: source.length
            val end = root?.endMark?.index?.let(::offset) ?: source.length
            return insert(start, end, defaults)
        }
        if (mapping == null) return source
        if (mapping.flowStyle == DumperOptions.FlowStyle.FLOW) {
            val start = offset(mapping.value.first().keyNode.startMark.index)
            return source.substring(0, start) +
                "canvas: {width: 320, height: 180, pixels-per-block: 42.7, distance: 3}, " + source.substring(start)
        }
        val end = offset(mapping.endMark.index)
        val indent = " ".repeat(mapping.value.first().keyNode.startMark.column)
        return insert(end, end, canvas.joinToString(newline) { indent + it } + newline)
    }

    fun write(completed: String) {
        if (completed == source) return
        val temp = Files.createTempFile(path.parent, ".arcmenu-defaults-", ".tmp")
        try {
            Files.writeString(temp, completed)
            if (Files.readString(path) != source) throw MenuFormatException("Menu file changed during loading; run reload again")
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    // SnakeYAML marks count Unicode code points, whereas substring uses UTF-16 offsets.
    private fun offset(index: Int) = source.offsetByCodePoints(0, index)

    private fun insert(start: Int, end: Int, text: String): String {
        val prefix = source.substring(0, start)
        val separator = if (prefix.isNotEmpty() && !prefix.endsWith('\n') && !prefix.endsWith('\r')) newline else ""
        return prefix + separator + text + source.substring(end)
    }
}
