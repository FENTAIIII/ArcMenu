package com.fentai.arcmenu.core.behavior

internal fun leafActions(action: MenuAction): List<MenuAction> = when (action) {
    is ConfiguredAction -> leafActions(action.action)
    is ActionLanguage.SequenceAction -> action.actions.flatMap(::leafActions)
    else -> listOf(action)
}
