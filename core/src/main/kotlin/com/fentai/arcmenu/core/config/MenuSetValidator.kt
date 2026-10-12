package com.fentai.arcmenu.core.config

import com.fentai.arcmenu.core.behavior.MenuAction
import com.fentai.arcmenu.core.behavior.OpenMenuAction
import com.fentai.arcmenu.core.behavior.RefreshAction
import com.fentai.arcmenu.core.behavior.leafActions
import com.fentai.arcmenu.core.model.GroupNode
import com.fentai.arcmenu.core.model.MenuDocument
import com.fentai.arcmenu.core.model.TextNode
import com.fentai.arcmenu.core.model.VisualNode

object MenuSetValidator {
    private val extensionRoute = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
    fun validate(documents: Map<String, MenuDocument>) {
        for (document in documents.values) {
            val definition = document.definition
            val deniedMenu = definition.permissionDeniedMenu
            if (deniedMenu.isNotEmpty()) {
                if (deniedMenu !in documents) {
                    throw MenuFormatException("${document.sourceName}.permission-denied-menu: target menu does not exist: $deniedMenu")
                }
                if (deniedMenu == definition.id) {
                    throw MenuFormatException("${document.sourceName}.permission-denied-menu: Must not redirect to the same menu")
                }
            }
            val refreshTargets = textIds(definition.frontend) + definition.backend.map { it.id }
            checkActions(document, "events.open", definition.events.open.flatMap { it.actions + it.deny }, documents, refreshTargets)
            checkActions(document, "events.close", definition.events.close.flatMap { it.actions + it.deny }, documents, refreshTargets)
            for (region in definition.backend) {
                checkActions(document, "backend.${region.id}.actions", region.actions.allActions().toList(), documents, refreshTargets)
                checkActions(document, "backend.${region.id}.deny", region.deny, documents, refreshTargets)
                checkActions(document, "backend.${region.id}.on-hover", region.onHover.flatMap { it.actions + it.deny }, documents, refreshTargets)
                checkActions(document, "backend.${region.id}.on-leave", region.onLeave.flatMap { it.actions + it.deny }, documents, refreshTargets)
            }
        }
        MenuEntrypointCompiler.compile(documents)
    }

    private fun checkActions(
        document: MenuDocument,
        path: String,
        actions: List<MenuAction>,
        documents: Map<String, MenuDocument>,
        refreshTargets: Set<String>,
    ) {
        val leaves = actions.flatMap(::leafActions)
        leaves.filterIsInstance<OpenMenuAction>().firstOrNull {
            !it.dynamicTarget && it.menuId !in documents && !extensionRoute.matches(it.menuId)
        }?.let {
            throw MenuFormatException("${document.sourceName}.$path: open target menu does not exist: ${it.menuId}")
        }
        leaves.filterIsInstance<RefreshAction>().firstOrNull {
            it.target?.split(';')?.any { target ->
                val value = target.trim()
                '%' !in value && '{' !in value && value !in refreshTargets
            } == true
        }?.let {
            throw MenuFormatException("${document.sourceName}.$path: refresh target does not exist or is not dynamic text/tooltip: ${it.target}")
        }
    }

    private fun textIds(nodes: List<VisualNode>): Set<String> = buildSet {
        fun visit(node: VisualNode) {
            when (node) {
                is TextNode -> add(node.properties.id)
                is GroupNode -> node.children.forEach(::visit)
                else -> Unit
            }
        }
        nodes.forEach(::visit)
    }
}
