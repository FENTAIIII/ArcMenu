package com.fentai.arcmenu.paper.api

import com.fentai.arcmenu.api.ArcMenuRoute
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

class ExtensionRoutes {
    private data class Registration(val owner: Plugin, val route: ArcMenuRoute)
    private val routes = linkedMapOf<String, Registration>()

    fun register(owner: Plugin, routeId: String, route: ArcMenuRoute) {
        require(owner.isEnabled) { "禁用的插件不能注册 ArcMenu 路由" }
        require(ROUTE.matches(routeId)) { "扩展路由必须为 namespace:path，例如 myaddon:shop" }
        val previous = routes[routeId]
        require(previous == null || previous.owner === owner) { "路由 $routeId 已由 ${previous?.owner?.name} 注册" }
        routes[routeId] = Registration(owner, route)
    }

    fun unregister(owner: Plugin) {
        routes.entries.removeIf { it.value.owner === owner }
    }

    /** null means there is no registered route; false is a route handler's explicit refusal. */
    fun dispatch(player: Player, routeId: String, arguments: List<String>): Boolean? {
        val registration = routes[routeId] ?: return null
        if (!registration.owner.isEnabled) return false
        return registration.route.open(player, arguments.toList())
    }

    fun ids(): Set<String> = routes.keys.toSet()

    private companion object {
        val ROUTE = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
    }
}
