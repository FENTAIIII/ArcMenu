package com.fentai.arcmenu.core.config

import com.fentai.arcmenu.core.behavior.MenuAction
import com.fentai.arcmenu.core.behavior.OpenMenuAction
import com.fentai.arcmenu.core.behavior.RefreshAction
import com.fentai.arcmenu.core.behavior.ConfiguredAction
import com.fentai.arcmenu.core.behavior.ActionLanguage
import com.fentai.arcmenu.core.model.GroupNode
import com.fentai.arcmenu.core.model.MenuDocument
import com.fentai.arcmenu.core.model.TextNode
import com.fentai.arcmenu.core.model.VisualNode

object MenuSetValidator {
    private val extensionRoute = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
    fun validate(documents: Map<String, MenuDocument>) {
        for (document in documents.values) {
            val definition = document.definition
            val refreshTargets = textIds(definition.frontend) + definition.backend.map { it.id }
            checkActions(document, "events.open", definition.events.open.flatMap { it.actions + it.deny }, documents, refreshTargets)
            checkActions(document, "events.close", definition.events.close.flatMap { it.actions + it.deny }, documents, refreshTargets)
            for (region in definition.backend) {
                checkActions(document, "backend.${region.id}.actions", region.actions.allActions().toList(), documents, refreshTargets)
                checkActions(document, "backend.${region.id}.deny", region.deny, documents, refreshTargets)
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
            throw MenuFormatException("${document.sourceName}.$path: open 目标菜单不存在: ${it.menuId}")
        }
        leaves.filterIsInstance<RefreshAction>().firstOrNull {
            it.target?.split(';')?.any { target ->
                val value = target.trim()
                '%' !in value && '{' !in value && value !in refreshTargets
            } == true
        }?.let {
            throw MenuFormatException("${document.sourceName}.$path: refresh 目标不存在或不是动态文字/tooltip: ${it.target}")
        }
    }

    private fun leafActions(action: MenuAction): List<MenuAction> = when (action) {
        is ConfiguredAction -> leafActions(action.action)
        is ActionLanguage.SequenceAction -> action.actions.flatMap(::leafActions)
        else -> listOf(action)
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
