package com.fentai.arcmenu.paper.editor

import com.fentai.arcmenu.core.config.LoadResult
import com.fentai.arcmenu.core.config.MenuParser
import com.fentai.arcmenu.core.config.TemplateLoadResult
import com.fentai.arcmenu.core.config.VisualTemplate
import com.fentai.arcmenu.core.model.MenuDocument
import com.fentai.arcmenu.core.model.MenuPoint
import com.fentai.arcmenu.paper.render.BukkitMenuRenderer
import com.fentai.arcmenu.paper.render.MenuViewMode
import com.fentai.arcmenu.paper.localization.LanguageManager
import com.fentai.arcmenu.paper.runtime.MenuSessions
import com.fentai.arcmenu.protocol.EditorProtocol
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.messaging.PluginMessageListener
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class EditorService(
    private val plugin: Plugin,
    private val menuDirectory: Path,
    private val documents: () -> Map<String, MenuDocument>,
    private val sessions: MenuSessions,
    private val renderer: BukkitMenuRenderer,
    private val applyCatalog: () -> LoadResult,
    private val templateDirectory: Path,
    private val templates: () -> Map<String, VisualTemplate>,
    private val reloadTemplates: () -> TemplateLoadResult,
    private val language: LanguageManager,
    private val images: () -> List<EditorProtocol.ImageSnapshot>,
) : PluginMessageListener, AutoCloseable {
    private data class Rate(var second: Long, var count: Int)

    private val parser = MenuParser()
    private val drafts = mutableMapOf<UUID, EditorDraft>()
    private val writers = mutableMapOf<String, UUID>()
    private val clientVersions = mutableMapOf<UUID, String>()
    private val rates = mutableMapOf<UUID, Rate>()
    private val nextMessage = AtomicInteger(1)

    init {
        plugin.server.messenger.registerIncomingPluginChannel(plugin, EditorProtocol.CHANNEL, this)
        plugin.server.messenger.registerOutgoingPluginChannel(plugin, EditorProtocol.CHANNEL)
    }

    fun open(player: Player, menuId: String): String {
        require(player.listeningPluginChannels.contains(EditorProtocol.CHANNEL)) {
            text(player, "editor.no-channel")
        }
        val document = documents()[menuId] ?: throw IllegalArgumentException(text(player, "editor.menu-missing", menuId))
        val heldBy = writers[menuId]
        require(heldBy == null || heldBy == player.uniqueId) { text(player, "editor.writer-conflict", menuId) }
        close(player)
        val file = menuDirectory.resolve(document.sourceName).normalize()
        require(file.startsWith(menuDirectory.normalize())) { text(player, "editor.source-outside") }
        val source = java.nio.file.Files.readString(file)
        val fresh = parser.parse(source, document.sourceName)
        require(fresh.definition.id == menuId) { text(player, "editor.id-changed") }
        val draft = EditorDraft(
            player.uniqueId, file, menuId, source, fresh.definition,
            validate = { definition -> renderer.validate(MenuDocument(definition, "", "editor:$menuId")) },
            parseSaved = { next ->
                parser.parse(next, document.sourceName).also(renderer::validate).definition
            },
        )
        writers[menuId] = player.uniqueId
        drafts[player.uniqueId] = draft
        sessions.openPreview(player, draft.definition, MenuViewMode.FRONTEND_PREVIEW)
        sendSnapshot(player, draft)
        return text(player, "editor.opened", menuId, EditorProtocol.VERSION,
            clientVersions[player.uniqueId] ?: text(player, "editor.connected"))
    }

    fun contains(player: Player): Boolean = drafts.containsKey(player.uniqueId)

    fun close(player: Player) {
        val draft = drafts.remove(player.uniqueId) ?: return
        writers.remove(draft.menuId, player.uniqueId)
        rates.remove(player.uniqueId)
        sessions.close(player, true)
    }

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel != EditorProtocol.CHANNEL) return
        if (!Bukkit.isPrimaryThread()) {
            plugin.server.scheduler.runTask(plugin, Runnable { onPluginMessageReceived(channel, player, message) })
            return
        }
        try {
            if (!allow(player)) throw IllegalArgumentException(text(player, "editor.throttled"))
            when (val packet = EditorProtocol.decode(message)) {
                is EditorProtocol.HelloPacket -> clientVersions[player.uniqueId] = packet.clientVersion().take(64)
                is EditorProtocol.ClosePacket -> close(player)
                else -> handle(player, packet)
            }
        } catch (error: Exception) {
            val revision = drafts[player.uniqueId]?.revision ?: -1
            send(player, EditorProtocol.ErrorPacket(revision, error.message ?: text(player, "editor.protocol-error")))
            drafts[player.uniqueId]?.let { sendSnapshot(player, it) }
        }
    }

    private fun handle(player: Player, packet: EditorProtocol.Packet) {
        require(player.hasPermission("arcmenu.admin")) { text(player, "editor.permission-lost") }
        val draft = drafts[player.uniqueId] ?: throw IllegalArgumentException(text(player, "editor.no-draft"))
        when (packet) {
            is EditorProtocol.MovePacket -> {
                require(packet.tab() == draft.activeTab) { wrongTab(player, "editor.operation-move") }
                val point = coordinates(packet.pointer(), draft)
                require(packet.grabOffsetX().isFinite() && packet.grabOffsetY().isFinite()) { text(player, "editor.invalid-drag-offset") }
                val geometry = draft.move(packet.revision(), packet.tab(), packet.nodeId(),
                    point.x - packet.grabOffsetX(), point.y - packet.grabOffsetY(),
                    packet.gestureId(), packet.finalUpdate())
                refreshGeometry(player, draft)
                ack(player, draft, EditorProtocol.OP_MOVE, geometry, point, coordinateMessage(player, packet.pointer(), point), packet.finalUpdate())
            }
            is EditorProtocol.ResizePacket -> {
                require(packet.tab() == draft.activeTab) { wrongTab(player, "editor.operation-resize") }
                val point = coordinates(packet.pointer(), draft)
                val geometry = draft.resize(packet.revision(), packet.tab(), packet.nodeId(), point.x, point.y,
                    packet.gestureId(), packet.finalUpdate())
                refreshGeometry(player, draft)
                ack(player, draft, EditorProtocol.OP_RESIZE, geometry, point, coordinateMessage(player, packet.pointer(), point), packet.finalUpdate())
            }
            is EditorProtocol.SwitchTabPacket -> {
                draft.switchTab(packet.tab())
                refresh(player, draft)
                ack(player, draft, EditorProtocol.OP_TAB, null, null,
                    text(player, if (packet.tab() == EditorProtocol.TAB_FRONTEND) "editor.switched-frontend" else "editor.switched-backend"))
            }
            is EditorProtocol.UndoPacket -> {
                draft.undo(packet.revision()); refresh(player, draft)
                ack(player, draft, EditorProtocol.OP_UNDO, null, null, text(player, "editor.undone"))
            }
            is EditorProtocol.RedoPacket -> {
                draft.redo(packet.revision()); refresh(player, draft)
                ack(player, draft, EditorProtocol.OP_REDO, null, null, text(player, "editor.redone"))
            }
            is EditorProtocol.SavePacket -> {
                val result = draft.save(packet.revision())
                ack(player, draft, EditorProtocol.OP_SAVE, null, null, saveMessage(player, result))
            }
            is EditorProtocol.ApplyPacket -> {
                val saved = saveMessage(player, draft.save(packet.revision()))
                val result = applyCatalog()
                require(result.applied) { text(player, "editor.apply-failed", result.errors.joinToString("; ")) }
                ack(player, draft, EditorProtocol.OP_APPLY, null, null, text(player, "editor.applied", saved, result.count))
            }
            is EditorProtocol.ProbePacket -> {
                require(packet.revision() == draft.revision) { text(player, "editor.revision-conflict", packet.revision(), draft.revision) }
                val point = coordinates(packet.pointer(), draft)
                sessions.markEditorPoint(player, point)
                ack(player, draft, EditorProtocol.OP_PROBE, null, point, coordinateMessage(player, packet.pointer(), point))
            }
            is EditorProtocol.CreatePacket -> {
                val id = draft.create(packet.revision(), packet.tab(), packet.kind(), packet.parentId(), packet.initialSource())
                refresh(player, draft)
                ack(player, draft, EditorProtocol.OP_CREATE, draft.geometry(id, packet.tab()), null, text(player, "editor.created", id))
            }
            is EditorProtocol.DeletePacket -> {
                draft.delete(packet.revision(), packet.tab(), packet.nodeId())
                refresh(player, draft)
                ack(player, draft, EditorProtocol.OP_DELETE, null, null, text(player, "editor.deleted", packet.nodeId()))
            }
            is EditorProtocol.DeleteManyPacket -> {
                require(packet.tab() == draft.activeTab) { wrongTab(player, "editor.operation-delete") }
                draft.deleteMany(packet.revision(), packet.tab(), packet.nodeIds())
                refresh(player, draft)
                ack(player, draft, EditorProtocol.OP_DELETE_MANY, null, null, text(player, "editor.deleted-many", packet.nodeIds().size))
            }
            is EditorProtocol.DuplicatePacket -> {
                require(packet.tab() == draft.activeTab) { wrongTab(player, "editor.operation-copy") }
                val ids = draft.duplicate(packet.revision(), packet.tab(), packet.nodeIds(),
                    packet.targetParentId(), packet.preserveParents())
                refresh(player, draft)
                ack(player, draft, EditorProtocol.OP_DUPLICATE,
                    draft.geometry(ids.first(), packet.tab()), null, text(player, "editor.duplicated", ids.size))
            }
            is EditorProtocol.GroupPacket -> {
                require(draft.activeTab == EditorProtocol.TAB_FRONTEND) { text(player, "editor.backend-no-group") }
                val id = draft.group(packet.revision(), packet.nodeIds())
                refresh(player, draft)
                ack(player, draft, EditorProtocol.OP_GROUP,
                    draft.geometry(id, EditorProtocol.TAB_FRONTEND), null, text(player, "editor.grouped", packet.nodeIds().size, id))
            }
            is EditorProtocol.ReorderPacket -> {
                require(packet.tab() == draft.activeTab) { wrongTab(player, "editor.operation-reorder") }
                draft.reorder(packet.revision(), packet.tab(), packet.nodeIds(), packet.beforeId())
                refreshGeometry(player, draft)
                ack(player, draft, EditorProtocol.OP_REORDER, null, null, text(player, "editor.reordered", packet.nodeIds().size))
            }
            is EditorProtocol.ReparentPacket -> {
                require(draft.activeTab == EditorProtocol.TAB_FRONTEND) { wrongTab(player, "editor.operation-reparent") }
                draft.reparent(packet.revision(), packet.nodeIds(), packet.targetParentId(), packet.beforeId())
                refresh(player, draft)
                ack(player, draft, EditorProtocol.OP_REPARENT, null, null,
                    text(player, "editor.reparented", packet.nodeIds().size,
                        packet.targetParentId().ifBlank { text(player, "editor.root-level") }))
            }
            is EditorProtocol.SetPropertyPacket -> {
                require(packet.tab() == draft.activeTab) { wrongTab(player, "editor.operation-property") }
                draft.setProperty(packet.revision(), packet.tab(), packet.nodeId(), packet.key(), packet.value(),
                    packet.gestureId(), packet.finalUpdate())
                if (packet.finalUpdate()) refresh(player, draft) else refreshGeometry(player, draft)
                val nextId = if (packet.key() == "id") packet.value() else packet.nodeId()
                ack(player, draft, EditorProtocol.OP_PROPERTY, draft.geometry(nextId, packet.tab()), null,
                    text(player, "editor.property-updated", nextId, packet.key()), packet.finalUpdate())
            }
            is EditorProtocol.SaveTemplatePacket -> {
                val source = draft.templateSource(packet.revision(), packet.nodeId(), packet.templateId())
                val file = templateDirectory.resolve("${packet.templateId()}.yml").normalize()
                require(file.startsWith(templateDirectory.normalize())) { text(player, "editor.template-outside") }
                require(!Files.exists(file)) { text(player, "editor.template-exists", packet.templateId()) }
                Files.createDirectories(templateDirectory)
                val temp = file.resolveSibling(".${file.fileName}.${UUID.randomUUID()}.tmp")
                Files.writeString(temp, source)
                try {
                    try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE) }
                    catch (_: Exception) { Files.move(temp, file) }
                    val loaded = reloadTemplates()
                    if (!loaded.applied) {
                        Files.deleteIfExists(file); reloadTemplates()
                        throw IllegalArgumentException(text(player, "editor.template-invalid", loaded.errors.joinToString("; ")))
                    }
                } finally { Files.deleteIfExists(temp) }
                ack(player, draft, EditorProtocol.OP_TEMPLATE_SAVE, null, null, text(player, "editor.template-saved", packet.templateId()))
            }
            is EditorProtocol.InstantiateTemplatePacket -> {
                val template = templates()[packet.templateId()] ?: throw IllegalArgumentException(text(player, "editor.template-missing", packet.templateId()))
                val id = draft.instantiate(packet.revision(), template)
                refresh(player, draft)
                ack(player, draft, EditorProtocol.OP_TEMPLATE_INSTANTIATE,
                    draft.geometry(id, EditorProtocol.TAB_FRONTEND), null, text(player, "editor.template-created", id))
            }
            is EditorProtocol.DeleteTemplatePacket -> {
                require(packet.revision() == draft.revision) {
                    text(player, "editor.revision-conflict", packet.revision(), draft.revision)
                }
                require(Regex("[a-z0-9][a-z0-9_-]*").matches(packet.templateId())) { text(player, "editor.template-id-invalid") }
                val template = templates()[packet.templateId()]
                    ?: throw IllegalArgumentException(text(player, "editor.template-missing", packet.templateId()))
                TemplateFileOperations.delete(templateDirectory, template, reloadTemplates)
                ack(player, draft, EditorProtocol.OP_TEMPLATE_DELETE, null, null, text(player, "editor.template-deleted", packet.templateId()))
            }
            is EditorProtocol.HelloPacket, is EditorProtocol.ClosePacket,
            is EditorProtocol.SnapshotPacket, is EditorProtocol.AckPacket, is EditorProtocol.ErrorPacket ->
                throw IllegalArgumentException(text(player, "editor.forbidden-packet"))
        }
    }

    private fun refresh(player: Player, draft: EditorDraft) {
        val mode = if (draft.activeTab == EditorProtocol.TAB_FRONTEND) MenuViewMode.FRONTEND_PREVIEW else MenuViewMode.BACKEND_PREVIEW
        sessions.updatePreview(player, draft.definition, mode)
    }

    private fun refreshGeometry(player: Player, draft: EditorDraft) {
        val mode = if (draft.activeTab == EditorProtocol.TAB_FRONTEND) MenuViewMode.FRONTEND_PREVIEW else MenuViewMode.BACKEND_PREVIEW
        sessions.updatePreviewGeometry(player, draft.definition, mode)
    }

    private fun ack(
        player: Player,
        draft: EditorDraft,
        operation: Byte,
        geometry: NodeGeometry?,
        point: MenuPoint?,
        message: String,
        includeSnapshot: Boolean = true,
    ) {
        if (point != null) sessions.markEditorPoint(player, point)
        val hit = point?.let(draft::hit).orEmpty()
        send(player, EditorProtocol.AckPacket(
            operation, draft.revision, geometry?.id.orEmpty(), geometry?.x ?: Double.NaN, geometry?.y ?: Double.NaN,
            geometry?.width ?: Double.NaN, geometry?.height ?: Double.NaN,
            point?.x ?: Double.NaN, point?.y ?: Double.NaN, hit, draft.dirty, draft.saved, message,
        ))
        if (includeSnapshot && operation != EditorProtocol.OP_PROBE) sendSnapshot(player, draft)
    }

    private fun sendSnapshot(player: Player, draft: EditorDraft) {
        val templateSnapshots = templates().values.sortedBy { it.id }.map { template ->
            fun count(nodes: List<com.fentai.arcmenu.core.model.VisualNode>): Int = nodes.sumOf { node ->
                1 + if (node is com.fentai.arcmenu.core.model.GroupNode) count(node.children) else 0
            }
            EditorProtocol.TemplateSnapshot(template.id, template.root.properties.id, count(listOf(template.root)))
        }
        send(player, draft.snapshot(serverVersion(), images(), templateSnapshots))
    }

    private fun coordinates(pointer: EditorProtocol.Pointer, draft: EditorDraft): MenuPoint {
        return editorCoordinates(pointer, draft.definition.canvas)
    }

    private fun coordinateMessage(player: Player, pointer: EditorProtocol.Pointer, point: MenuPoint): String {
        val delta = maxOf(abs(pointer.clientX() - point.x), abs(pointer.clientY() - point.y))
        return text(player, "editor.coordinate", decimal(pointer.clientX(), 2), decimal(pointer.clientY(), 2),
            decimal(point.x, 2), decimal(point.y, 2), decimal(delta, 4))
    }

    private fun send(player: Player, packet: EditorProtocol.Packet) {
        EditorProtocol.frame(nextMessage.getAndIncrement(), EditorProtocol.encode(packet)).forEach { frame ->
            player.sendPluginMessage(plugin, EditorProtocol.CHANNEL, frame)
        }
    }

    private fun allow(player: Player): Boolean {
        val second = System.currentTimeMillis() / 1000
        val rate = rates.computeIfAbsent(player.uniqueId) { Rate(second, 0) }
        if (rate.second != second) { rate.second = second; rate.count = 0 }
        rate.count++
        return rate.count <= 80
    }

    private fun serverVersion(): String = Bukkit.getMinecraftVersion()

    private fun text(player: Player, key: String, vararg arguments: Any?): String =
        language.text(player, key, *arguments)

    private fun wrongTab(player: Player, operationKey: String): String =
        text(player, "editor.wrong-tab", text(player, operationKey))

    private fun saveMessage(player: Player, result: EditorSaveResult): String = if (result.changed) {
        text(player, "editor.saved", result.fileName, result.backupName)
    } else text(player, "editor.saved-none")

    private fun decimal(value: Double, places: Int): String =
        String.format(java.util.Locale.ROOT, "%.${places}f", value)

    fun closeAll() {
        drafts.keys.toList().mapNotNull(Bukkit::getPlayer).forEach(::close)
        drafts.clear(); writers.clear(); rates.clear()
    }

    override fun close() {
        closeAll()
        clientVersions.clear()
        plugin.server.messenger.unregisterIncomingPluginChannel(plugin, EditorProtocol.CHANNEL, this)
        plugin.server.messenger.unregisterOutgoingPluginChannel(plugin, EditorProtocol.CHANNEL)
    }
}
