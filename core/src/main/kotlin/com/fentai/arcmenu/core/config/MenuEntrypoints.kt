package com.fentai.arcmenu.core.config

import com.fentai.arcmenu.core.model.MenuDocument

data class MenuEntrypoints(
    val mainMenuId: String,
    val commands: Map<String, String>,
)

/** Compiles the single server shortcut and command labels from a complete menu set. */
object MenuEntrypointCompiler {
    fun compile(documents: Map<String, MenuDocument>): MenuEntrypoints {
        val mainMenus = documents.values.filter { it.definition.mainMenu }
        if (mainMenus.size != 1) {
            val found = mainMenus.joinToString { "${it.definition.id} (${it.sourceName})" }.ifEmpty { "无" }
            throw MenuFormatException("menus: 必须有且仅有一个 main-menu: true；当前为 $found")
        }

        val commands = linkedMapOf<String, String>()
        val owners = linkedMapOf<String, MenuDocument>()
        documents.values.forEach { document ->
            document.definition.openCommands.forEach { label ->
                val previous = owners.putIfAbsent(label, document)
                if (previous != null) {
                    throw MenuFormatException(
                        "${document.sourceName}.open-commands: /$label 已由 ${previous.sourceName} 的菜单 ${previous.definition.id} 使用",
                    )
                }
                commands[label] = document.definition.id
            }
        }
        return MenuEntrypoints(mainMenus.single().definition.id, commands.toMap())
    }
}
