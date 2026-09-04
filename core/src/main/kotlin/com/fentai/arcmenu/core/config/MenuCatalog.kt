package com.fentai.arcmenu.core.config

import com.fentai.arcmenu.core.model.MenuDocument
import java.nio.file.Files
import java.nio.file.Path

data class LoadResult(val applied: Boolean, val count: Int, val errors: List<String>)

/** Publishes only a complete, valid set. A failed reload leaves the previous catalog intact. */
class MenuCatalog(private val parser: MenuParser = MenuParser()) {
    var documents: Map<String, MenuDocument> = emptyMap()
        private set

    fun reload(directory: Path, validate: (MenuDocument) -> Unit = {}): LoadResult =
        reload(directory, validate) {}

    fun reload(
        directory: Path,
        validate: (MenuDocument) -> Unit,
        validateSet: (Map<String, MenuDocument>) -> Unit,
    ): LoadResult {
        val candidates = linkedMapOf<String, MenuDocument>()
        val errors = mutableListOf<String>()
        try {
            Files.walk(directory).use { files ->
                files.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".yml") }.sorted().forEach { path ->
                    try {
                        val document = parser.parse(Files.readString(path), directory.relativize(path).toString())
                        validate(document)
                        val id = document.definition.id
                        if (candidates.containsKey(id)) throw MenuFormatException("$path: 菜单 ID $id 与 ${candidates.getValue(id).sourceName} 重复")
                        candidates[id] = document
                    } catch (error: Exception) {
                        errors += "${path.fileName}: ${error.message ?: error.javaClass.simpleName}"
                    }
                }
            }
        } catch (error: Exception) {
            errors += "无法读取菜单目录 $directory: ${error.message}"
        }
        if (errors.isEmpty()) try {
            validateSet(candidates)
        } catch (error: Exception) {
            errors += error.message ?: error.javaClass.simpleName
        }
        if (errors.isNotEmpty()) return LoadResult(false, documents.size, errors.toList())
        documents = candidates.toMap()
        return LoadResult(true, documents.size, emptyList())
    }
}
