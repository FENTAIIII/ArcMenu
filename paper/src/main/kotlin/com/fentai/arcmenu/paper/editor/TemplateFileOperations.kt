package com.fentai.arcmenu.paper.editor

import com.fentai.arcmenu.core.config.TemplateLoadResult
import com.fentai.arcmenu.core.config.VisualTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

internal object TemplateFileOperations {
    private val yamlName = Regex("[^/\\\\]+\\.ya?ml", RegexOption.IGNORE_CASE)

    fun delete(directory: Path, template: VisualTemplate, reload: () -> TemplateLoadResult) {
        val root = directory.toAbsolutePath().normalize()
        val relative = Path.of(template.sourceName)
        require(!relative.isAbsolute && relative.nameCount == 1 && yamlName.matches(relative.fileName.toString())) {
            "模板源文件名无效: ${template.sourceName}"
        }
        val file = root.resolve(relative).normalize()
        require(file.parent == root) { "模板路径越过 templates 目录" }
        require(Files.isRegularFile(file)) { "模板源文件不存在: ${template.sourceName}" }

        val backup = file.resolveSibling(".${file.fileName}.${UUID.randomUUID()}.deleting")
        var moved = false
        try {
            move(file, backup)
            moved = true
            val loaded = reload()
            require(loaded.applied) { "删除后模板目录校验失败: ${loaded.errors.joinToString("；")}" }
            Files.delete(backup)
            moved = false
        } catch (error: Exception) {
            if (moved && Files.exists(backup)) {
                try {
                    move(backup, file)
                    reload()
                } catch (restoreError: Exception) {
                    error.addSuppressed(restoreError)
                    throw IllegalStateException("删除模板失败，且无法恢复 ${template.sourceName}", error)
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
