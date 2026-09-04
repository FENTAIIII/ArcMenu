package com.fentai.arcmenu.paper.api

import com.fentai.arcmenu.api.*
import com.fentai.arcmenu.paper.input.PointerController
import com.fentai.arcmenu.paper.input.PointerMode
import com.fentai.arcmenu.paper.runtime.MenuSessions
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID

internal class ArcMenuApiImpl(
    private val sessions: MenuSessions,
    private val routes: ExtensionRoutes,
    private val applications: ApplicationRegistry,
    private val pointer: PointerController,
) : ArcMenuApi {
    override fun apiVersion(): Int = sync { 1 }

    override fun capabilities(): Set<ArcMenuCapability> = sync { ArcMenuCapability.entries.toSet() }

    override fun open(player: Player, menuId: String, arguments: List<String>): Boolean = sync {
        sessions.open(player, menuId.lowercase(), remember = true, arguments = arguments.toList())
    }

    override fun close(player: Player) = sync<Unit> { sessions.close(player, true) }

    override fun openApplication(player: Player, applicationId: String, arguments: List<String>): Boolean = sync {
        sessions.openApplication(player, applicationId.lowercase(), remember = true, arguments = arguments.toList())
    }

    override fun playAnimation(player: Player, animationId: String): Boolean = sync {
        sessions.playAnimation(player, animationId)
    }

    override fun stopAnimation(player: Player, animationId: String): Boolean = sync {
        sessions.stopAnimation(player, animationId)
    }

    override fun animations(player: Player): Set<String> = sync { sessions.animations(player) }

    override fun registerRoute(owner: Plugin, routeId: String, route: ArcMenuRoute) = sync<Unit> {
        routes.register(owner, routeId, route)
    }

    override fun unregisterRoutes(owner: Plugin) = sync<Unit> { routes.unregister(owner) }

    override fun dispatchRoute(player: Player, routeId: String, arguments: List<String>): Boolean = sync {
        routes.dispatch(player, routeId, arguments) ?: false
    }

    override fun registerApplication(
        owner: Plugin,
        applicationId: String,
        application: ArcMenuApplication,
    ): ArcMenuApplicationHandle = registerApplication(owner, applicationId, ArcMenuApplicationOptions.DEFAULT, application)

    override fun registerApplication(
        owner: Plugin,
        applicationId: String,
        options: ArcMenuApplicationOptions,
        application: ArcMenuApplication,
    ): ArcMenuApplicationHandle = sync {
        val registration = applications.register(owner, applicationId, options, application)
        ApplicationHandle(registration.token, registration.id, owner)
    }

    override fun unregisterApplications(owner: Plugin) = sync<Unit> {
        sessions.closeApplications(owner)
        applications.unregister(owner)
    }

    override fun applications(): Set<String> = sync { applications.ids() }

    override fun activeApplication(player: Player): String? = sync { sessions.activeApplication(player) }

    override fun surface(player: Player): ArcMenuSurface? = sync { sessions.surface(player) }

    override fun pointer(player: Player): ArcMenuPoint? = sync { sessions.pointerPoint(player) }

    override fun pointerMode(player: Player): ArcMenuPointerMode = sync {
        ArcMenuPointerMode.valueOf(pointer.mode(player).name)
    }

    override fun setPointerMode(player: Player, mode: ArcMenuPointerMode): Boolean = sync {
        val result = pointer.setPreference(player, PointerMode.valueOf(mode.name))
        if (result.accepted) sessions.refreshPointerMode(player)
        result.accepted
    }

    override fun pointerPolicy(): ArcMenuPointerPolicy = sync {
        ArcMenuPointerPolicy.valueOf(pointer.settings.policy.name)
    }

    private fun <T> sync(operation: () -> T): T {
        check(Bukkit.isPrimaryThread()) { "ArcMenu API 必须在服务端主线程调用" }
        return operation()
    }

    private inner class ApplicationHandle(
        private val token: UUID,
        override val applicationId: String,
        override val owner: Plugin,
    ) : ArcMenuApplicationHandle {
        override fun isRegistered(): Boolean = sync { applications.contains(token) }

        override fun unregister() = sync<Unit> {
            sessions.closeApplicationRegistration(token)
            applications.unregister(token)
        }
    }
}
