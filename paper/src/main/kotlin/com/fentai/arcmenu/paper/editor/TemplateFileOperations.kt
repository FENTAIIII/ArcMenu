package com.fentai.arcmenu.paper.editor

import com.fentai.arcmenu.core.config.TemplateLoadResult
import com.fentai.arcmenu.core.config.VisualTemplate
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

internal object TemplateFileOperations {
    private val yamlName = Regex("[^/\\\\]+\\.ya?ml", RegexOption.IGNORE_CASE)

    fun delete(directory: Path, template: VisualTemplate, reload: () -> TemplateLoadResult) {
        val root = directory.toAbsolutePath().normalize()
        val relative = Path.of(template.sourceName)
        require(!relative.isAbsolute && relative.none { it.toString() in setOf(".", "..") } && yamlName.matches(relative.fileName.toString())) {
            "Invalid template source path: ${template.sourceName}"
        }
        val file = root.resolve(relative).normalize()
        require(file.startsWith(root) && file != root) { "Template path escapes the templates directory" }
        var ancestor = file
        while (ancestor != root) {
            require(!Files.isSymbolicLink(ancestor)) { "Template paths must not traverse symbolic links" }
            ancestor = ancestor.parent
        }
        require(Files.isRegularFile(file, NOFOLLOW_LINKS)) { "Template source file does not exist: ${template.sourceName}" }

        val backup = file.resolveSibling(".${file.fileName}.${UUID.randomUUID()}.deleting")
        var moved = false
        try {
            move(file, backup)
            moved = true
            val loaded = reload()
            require(loaded.applied) { "Template directory validation failed after deletion: ${loaded.errors.joinToString("; ")}" }
            Files.delete(backup)
            moved = false
        } catch (error: Exception) {
            if (moved && Files.exists(backup)) {
                try {
                    move(backup, file)
                    reload()
                } catch (restoreError: Exception) {
                    error.addSuppressed(restoreError)
                    throw IllegalStateException("Template deletion failed and ${template.sourceName} could not be restored", error)
                }
            }
            throw error
        }
    }

    private fun move(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(source, target)
        }
    }
}
