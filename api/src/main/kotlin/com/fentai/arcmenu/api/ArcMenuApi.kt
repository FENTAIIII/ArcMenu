package com.fentai.arcmenu.api

import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/** Public Java API published through Bukkit's ServicesManager. All methods run on the server thread. */
interface ArcMenuApi {
    fun apiVersion(): Int
    fun capabilities(): Set<ArcMenuCapability>
    fun open(player: Player, menuId: String, arguments: List<String>): Boolean
    fun open(player: Player, menuId: String): Boolean = open(player, menuId, emptyList())
    fun openApplication(player: Player, applicationId: String, arguments: List<String>): Boolean
    fun openApplication(player: Player, applicationId: String): Boolean = openApplication(player, applicationId, emptyList())
    fun close(player: Player)
    fun playAnimation(player: Player, animationId: String): Boolean
    fun stopAnimation(player: Player, animationId: String): Boolean
    fun animations(player: Player): Set<String>
    fun registerRoute(owner: Plugin, routeId: String, route: ArcMenuRoute)
    fun unregisterRoutes(owner: Plugin)
    fun dispatchRoute(player: Player, routeId: String, arguments: List<String>): Boolean
    fun registerApplication(owner: Plugin, applicationId: String, application: ArcMenuApplication): ArcMenuApplicationHandle
    fun registerApplication(
        owner: Plugin,
        applicationId: String,
        options: ArcMenuApplicationOptions,
        application: ArcMenuApplication,
    ): ArcMenuApplicationHandle
    fun unregisterApplications(owner: Plugin)
    fun applications(): Set<String>
    fun activeApplication(player: Player): String?
    fun surface(player: Player): ArcMenuSurface?
    fun pointer(player: Player): ArcMenuPoint?
    fun pointerMode(player: Player): ArcMenuPointerMode
    /** Returns false when server policy rejects the requested player preference. */
    fun setPointerMode(player: Player, mode: ArcMenuPointerMode): Boolean
    fun pointerPolicy(): ArcMenuPointerPolicy
}

/** A namespaced virtual route owned by an addon plugin. Java may use this as a SAM interface. */
fun interface ArcMenuRoute {
    fun open(player: Player, arguments: List<String>): Boolean
}

enum class ArcMenuPointerMode { TOUCH, MOUSE }
enum class ArcMenuPointerPolicy { PLAYER_CHOICE, FORCE_TOUCH, FORCE_MOUSE }

enum class ArcMenuCapability {
    APPLICATION_SESSIONS,
    APPLICATION_NAVIGATION,
    SCREEN_SURFACE,
    POINTER_EVENTS,
    MOUSE_HOTBAR_SCROLL,
    TRACKED_RESOURCES,
    SURFACE_REANCHOR,
}
