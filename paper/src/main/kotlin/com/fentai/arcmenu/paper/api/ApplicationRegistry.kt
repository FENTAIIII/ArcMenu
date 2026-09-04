package com.fentai.arcmenu.paper.api

import com.fentai.arcmenu.api.ArcMenuApplication
import com.fentai.arcmenu.api.ArcMenuApplicationOptions
import org.bukkit.plugin.Plugin
import java.util.UUID

internal data class RegisteredApplication(
    val token: UUID,
    val owner: Plugin,
    val id: String,
    val options: ArcMenuApplicationOptions,
    val application: ArcMenuApplication,
)

class ApplicationRegistry internal constructor() {
    private val registrations = linkedMapOf<String, RegisteredApplication>()

    internal fun register(
        owner: Plugin,
        applicationId: String,
        options: ArcMenuApplicationOptions,
        application: ArcMenuApplication,
    ): RegisteredApplication {
        require(owner.isEnabled) { "禁用的插件不能注册 ArcMenu 应用" }
        val id = normalize(applicationId)
        require(registrations[id] == null) { "应用 $id 已由 ${registrations[id]?.owner?.name} 注册" }
        validate(options)
        return RegisteredApplication(UUID.randomUUID(), owner, id, options, application).also { registrations[id] = it }
    }

    internal fun get(applicationId: String): RegisteredApplication? = registrations[applicationId.lowercase()]

    internal fun contains(token: UUID): Boolean = registrations.values.any { it.token == token }

    internal fun unregister(token: UUID): RegisteredApplication? {
        val entry = registrations.entries.firstOrNull { it.value.token == token } ?: return null
        registrations.remove(entry.key)
        return entry.value
    }

    internal fun unregister(owner: Plugin): List<RegisteredApplication> {
        val removed = registrations.values.filter { it.owner === owner }
        registrations.entries.removeIf { it.value.owner === owner }
        return removed
    }

    internal fun ids(): Set<String> = registrations.keys.toSet()

    private fun normalize(value: String): String {
        val id = value.trim().lowercase()
        require(ID.matches(id)) { "应用 ID 必须为 namespace:path，例如 myaddon:shop" }
        return id
    }

    private fun validate(options: ArcMenuApplicationOptions) {
        require(options.tickInterval in 1..1200) { "应用 tickInterval 必须介于 1 和 1200" }
        val canvas = options.canvas
        require(canvas.width.isFinite() && canvas.width > 0.0) { "应用画布宽度必须为有限正数" }
        require(canvas.height.isFinite() && canvas.height > 0.0) { "应用画布高度必须为有限正数" }
        require(canvas.pixelsPerBlock.isFinite() && canvas.pixelsPerBlock > 0.0) { "应用画布 pixelsPerBlock 必须为有限正数" }
        require(canvas.designDistance.isFinite() && canvas.designDistance > 0.0) { "应用画布 designDistance 必须为有限正数" }
    }

    private companion object {
        val ID = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
    }
}
