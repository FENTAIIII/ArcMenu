package com.fentai.arcmenu.core.config

import com.fentai.arcmenu.core.model.MenuDocument
import java.nio.file.Files
import java.nio.file.Path

data class LoadResult(val applied: Boolean, val count: Int, val errors: List<String>)

/** Publishes only a complete, valid set. A failed reload leaves the previous catalog intact. */
class MenuCatalog(private val parser: MenuParser = MenuParser()) {
    @Volatile var documents: Map<String, MenuDocument> = emptyMap()
        private set

    fun reload(directory: Path, validate: (MenuDocument) -> Unit = {}): LoadResult =
        reload(directory, validate, {})

    fun reload(
        directory: Path,
        validate: (MenuDocument) -> Unit,
        validateSet: (Map<String, MenuDocument>) -> Unit,
        writeDefaults: Boolean = true,
    ): LoadResult {
        val candidates = linkedMapOf<String, MenuDocument>()
        val errors = mutableListOf<String>()
        val sources = mutableListOf<MenuFileDefaults>()
        val completed = linkedMapOf<MenuFileDefaults, String>()
        try {
            Files.walk(directory).use { files ->
                files.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".yml") }.sorted().forEach { path ->
                    try {
                        sources += MenuFileDefaults(path, Files.readString(path))
                    } catch (error: Exception) {
                        errors += "${path.fileName}: ${error.message ?: error.javaClass.simpleName}"
                    }
                }
            }
        } catch (error: Exception) {
            errors += "Cannot read menu directory $directory: ${error.message}"
        }
        // Reserve every authored ID first, including menus sorted after an empty file.
        val usedIds = sources.mapNotNull { it.existingId }.toMutableSet()
        for (file in sources) try {
            val source = file.complete(usedIds)
            val document = parser.parse(source, directory.relativize(file.path).toString())
            validate(document)
            val id = document.definition.id
            if (candidates.containsKey(id)) throw MenuFormatException("${file.path}: Menu ID $id duplicates ${candidates.getValue(id).sourceName}")
            candidates[id] = document
            completed[file] = source
        } catch (error: Exception) {
            errors += "${file.path.fileName}: ${error.message ?: error.javaClass.simpleName}"
        }
        if (errors.isEmpty()) try {
            validateSet(candidates)
        } catch (error: Exception) {
            errors += error.message ?: error.javaClass.simpleName
        }
        if (errors.isNotEmpty()) return LoadResult(false, documents.size, errors.toList())
        if (writeDefaults) for ((file, source) in completed) try {
            file.write(source)
        } catch (error: Exception) {
            return LoadResult(false, documents.size, listOf("${file.path.fileName}: Cannot populate menu file defaults: ${error.message}"))
        }
        documents = candidates.toMap()
        return LoadResult(true, documents.size, emptyList())
    }
}
