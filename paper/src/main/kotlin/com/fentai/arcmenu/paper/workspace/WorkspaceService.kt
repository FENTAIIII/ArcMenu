package com.fentai.arcmenu.paper.workspace

import com.fentai.arcmenu.paper.localization.localizedRequire
import com.fentai.arcmenu.paper.localization.localizedError
import com.fentai.arcmenu.core.config.VisualTemplate
import com.fentai.arcmenu.core.model.MenuDocument
import com.fentai.arcmenu.core.pack.*
import com.fentai.arcmenu.paper.render.MenuViewMode
import com.fentai.arcmenu.paper.editor.EditorService
import com.fentai.arcmenu.paper.localization.LanguageManager
import com.fentai.arcmenu.paper.localization.localizedStateFailure
import com.fentai.arcmenu.paper.pack.ArcPackService
import com.fentai.arcmenu.paper.pack.LocalArcPack
import com.fentai.arcmenu.paper.pack.ArcPackPreviewScene
import com.fentai.arcmenu.paper.platform.ArcScheduler
import com.fentai.arcmenu.paper.runtime.MenuSessions
import com.fentai.arcmenu.protocol.EditorProtocol
import com.fentai.arcmenu.protocol.WorkspaceProtocol
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.messaging.PluginMessageListener
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Base64
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.math.tan

internal class WorkspaceService(
    private val plugin: Plugin,
    private val editor: EditorService,
    private val sessions: MenuSessions,
    private val language: LanguageManager,
    private val documents: () -> Map<String, MenuDocument>,
    private val refresh: () -> Unit,
    private val marketUrl: () -> String,
    private val marketCertificate: () -> String,
    private val notify: (CommandSender, String, Array<out Any?>) -> Unit,
    private val resourcePending: () -> Boolean = { false },
    private val templates: () -> Map<String, VisualTemplate> = { emptyMap() },
) : PluginMessageListener, AutoCloseable {
    private val root = plugin.dataFolder.toPath().toAbsolutePath().normalize()
    private val json = Gson()
    private val fileLock = Any()
    private val projects = WorkspaceProjects(root, documents, refresh, ::isEditing, templates)
    private val packs = ArcPackService(root, { documents().values }, refresh, templates)
    private val gateway = MarketGateway(marketUrl, marketCertificate, root.resolve("packs/.downloads"))
    private val nextMessage = AtomicInteger(1)
    private val worker = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(32),
        ThreadFactory { task -> Thread(task, "ArcMenu-market-io").apply { isDaemon = true } })
    private data class Rate(var second: Long = 0, var count: Int = 0)
    private val rates = ConcurrentHashMap<UUID, Rate>()
    private data class Capture(var project: WorkspaceSelection)
    private val captures = ConcurrentHashMap<UUID, Capture>()
    private data class PreviewUpload(val file: Path, val count: Int, var next: Int = 0, var bytes: Long = 0)
    private val uploads = ConcurrentHashMap<String, PreviewUpload>()

    init {
        plugin.server.messenger.registerIncomingPluginChannel(plugin, WorkspaceProtocol.CHANNEL, this)
        plugin.server.messenger.registerOutgoingPluginChannel(plugin, WorkspaceProtocol.CHANNEL)
    }
    fun show(player: Player, view: String) {
        localizedRequire(player.listeningPluginChannels.contains(WorkspaceProtocol.CHANNEL), "workspace.editor-required", "Install the current ArcMenu Editor to open this workspace")
        if (view == "market") requireMarketOperator(player)
        else localizedRequire(player.hasPermission("arcmenu.admin"), "workspace.permission-required", "Workspace permission is required")
        val content = if (player.hasPermission("arcmenu.admin")) listing(player) else linkedMapOf<String, Any>("marketUrl" to marketUrl(), "marketCertificateSha256" to marketCertificate(), "capabilities" to capabilities(player))
        content["view"] = view
        reply(player, 0, "show", content)
    }
    private fun capabilities(player: Player): Map<String, Boolean> = mapOf(
        "effects" to false, "shaders" to false, "gifs" to true,
        "market" to (canAccessMarket(player)))

    private fun requireMarketOperator(sender: CommandSender) {
        localizedRequire(canAccessMarket(sender),
            "workspace.market-op-required", "Only operators may use the marketplace")
    }

    private fun listing(player: Player): LinkedHashMap<String, Any> = synchronized(fileLock) {
        linkedMapOf("projects" to projects.list(), "packs" to packs.list().map {
            mapOf("id" to it.manifest.id, "title" to it.manifest.title, "version" to it.manifest.version)
        }, "localPacks" to packs.listExports().map(::localPack), "marketUrl" to marketUrl(), "marketCertificateSha256" to marketCertificate(), "resourcePending" to resourcePending(), "capabilities" to capabilities(player))
    }
    private fun localPack(pack: LocalArcPack): Map<String, Any> = linkedMapOf(
        "filename" to pack.filename, "id" to pack.manifest.id, "version" to pack.manifest.version,
        "title" to pack.manifest.title, "description" to pack.manifest.description, "author" to pack.manifest.author,
        "projects" to pack.manifest.projects, "dependencies" to pack.manifest.dependencies, "size" to pack.size,
        "modifiedAt" to pack.modifiedAt, "prepared" to pack.prepared,
        "previews" to pack.previews.map { it.removePrefix("previews/") }, "cover" to pack.cover.removePrefix("previews/"),
    )
    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel != WorkspaceProtocol.CHANNEL) return
        if (!ArcScheduler.owns(player)) { ArcScheduler.execute(plugin, player, Runnable { onPluginMessageReceived(channel, player, message) }); return }
        var packet: WorkspaceProtocol.Request? = null
        try {
            localizedRequire(message.size <= 28_000, "workspace.request-too-large", "Workspace request is too large")
            packet = WorkspaceProtocol.decode(message) as? WorkspaceProtocol.Request ?: localizedError("workspace.requests-only", "Only workspace requests are accepted")
            localizedRequire(packet.requestId() > 0, "workspace.request-id-invalid", "Invalid workspace request ID")
            val rate = rates.computeIfAbsent(player.uniqueId) { Rate() }
            val second = System.nanoTime() / 1_000_000_000
            if (rate.second != second) { rate.second = second; rate.count = 0 }
            localizedRequire(++rate.count <= 40, "workspace.throttled", "Too many workspace requests")
            val args = JsonParser.parseString(packet.json()).asJsonObject
            if (packet.action() == "market-config") {
                requireMarketOperator(player)
                reply(player, packet.requestId(), packet.action(), mapOf("marketUrl" to marketUrl(), "marketCertificateSha256" to marketCertificate(), "capabilities" to capabilities(player)))
                return
            }
            if (packet.action() in setOf("install", "publish")) requireMarketOperator(player)
            localizedRequire(player.hasPermission("arcmenu.admin"), "workspace.admin-required", "Administrator permission is required")
            handle(player, packet, args)
        } catch (error: Exception) { failure(player, packet?.requestId() ?: 0, packet?.action() ?: "error", error) }
    }
    private fun handle(player: Player, packet: WorkspaceProtocol.Request, args: JsonObject) {
        val requestId = packet.requestId(); val action = packet.action()
        when (action) {
            "list" -> reply(player, requestId, action, listing(player))
            "open" -> {
                val selected = selection(args)
                localizedRequire(selected.type != "template", "workspace.template-preview-only", "View templates with a read-only preview")
                localizedRequire(documents().containsKey(selected.id), "workspace.project-missing", "Project not found: ${selected.id}", selected.id)
                localizedRequire(!editor.hasUnsavedDraft(player) || args.get("confirmDiscard")?.asBoolean == true, "workspace.discard-required", "Save or explicitly discard the current draft before opening another project")
                restoreCapture(player)
                editor.open(player, selected.id)
                reply(player, requestId, action, mapOf("type" to selected.type, "id" to selected.id))
            }
            "create", "configure", "delete" -> synchronized(fileLock) {
                when (action) {
                    "create" -> projects.create(args)
                    "configure" -> projects.configure(args)
                    else -> {
                        localizedRequire(args.get("confirm")?.asBoolean == true, "workspace.delete-confirmation", "Confirm the selected projects before deleting")
                        val selected = selections(args)
                        projects.delete(selected)
                        if (selected.any { it.type == "template" }) editor.refreshSnapshot(player)
                    }
                }
                reply(player, requestId, action, listing(player))
            }
            "export" -> {
                val selections = selections(args)
                selections.forEach { localizedRequire(!isEditing(it), "workspace.export-close-required", "Save and close ${it.type} ${it.id} before exporting", it.type, it.id) }
                val request = ArcPackExportRequest(args.required("id"), args.string("version", "1.0.0"), args.required("title"),
                    args.string("description"), ArcPackAuthor(args.string("authorUuid", player.uniqueId.toString()), args.string("authorName", player.name)),
                    selections.map { ArcPackSelection(it.type, it.id) },
                    args.getAsJsonArray("dependencies")?.map { it.asString }.orEmpty(), args.get("allowCe")?.asBoolean == true)
                async(player, packet, { synchronized(fileLock) { packs.export(request) } }) { result ->
                    reply(player, requestId, action, mapOf("filename" to result.archive.fileName.toString(), "manifest" to result.manifest,
                        "warnings" to result.warnings, "dependencies" to result.manifest.dependencies,
                        "localPacks" to packs.listExports().map(::localPack),
                        "localPack" to localPack(packs.listExports().first { it.filename == result.archive.fileName.toString() })))
                }
            }
            "pack-configure" -> async(player, packet, { synchronized(fileLock) {
                packs.configureExport(args.required("filename"), args.required("title"), args.required("description"))
            } }) { reply(player, requestId, action, listing(player)) }
            "pack-delete" -> {
                localizedRequire(args.get("confirm")?.asBoolean == true, "workspace.pack-delete-confirmation", "Confirm the selected packages before deleting")
                val names = args.getAsJsonArray("filenames")?.map { it.asString } ?: localizedError("workspace.pack-selection-required", "Select package files before deleting")
                async(player, packet, { synchronized(fileLock) { packs.deleteExports(names) } }) { backups ->
                    reply(player, requestId, action, listing(player).apply { put("backups", backups) })
                }
            }
            "pack-preview" -> async(player, packet, { synchronized(fileLock) { packs.previewThumbnail(args.required("filename"), args.required("name")) } }) { bytes ->
                reply(player, requestId, action, mapOf("filename" to args.required("filename"), "name" to args.required("name"),
                    "width" to 320, "height" to 180, "data" to Base64.getEncoder().encodeToString(bytes)))
            }
            "pack-prepare" -> async(player, packet, { synchronized(fileLock) {
                val filename = args.required("filename")
                packs.attachExportPreviews(filename, stagedPreviews(player.uniqueId, filename))
                ArcPackIO.inspect(exportFile(filename)).projects.forEach { project ->
                    Files.deleteIfExists(previewDirectory(player.uniqueId, filename).resolve("${project.type}-${project.id}.png"))
                }
                filename
            } }) { filename -> reply(player, requestId, action, listing(player).apply {
                put("localPack", localPack(packs.listExports().first { it.filename == filename }))
            }) }
            "install" -> async(player, packet, { gateway.download(args.required("id"), args.string("version"), args.string("token")) }) { file ->
                try {
                    val result = synchronized(fileLock) { packs.install(file) }
                    if (result.pack.manifest.projects.any { it.type == "template" }) editor.refreshSnapshot(player)
                    reply(player, requestId, action, listing(player).apply { put("installed", result.pack.manifest.id); put("warnings", result.warnings) })
                } finally { Files.deleteIfExists(file) }
            }
            "import" -> synchronized(fileLock) {
                val result = packs.install(exportFile(args.required("filename")))
                if (result.pack.manifest.projects.any { it.type == "template" }) editor.refreshSnapshot(player)
                reply(player, requestId, action, listing(player).apply { put("installed", result.pack.manifest.id); put("warnings", result.warnings) })
            }
            "uninstall" -> synchronized(fileLock) {
                val id = args.required("id")
                val installed = packs.list().firstOrNull { it.manifest.id == id } ?: localizedError("workspace.pack-not-installed", "Package is not installed: $id", id)
                installed.manifest.projects.forEach { project -> localizedRequire(!isEditing(WorkspaceSelection(project.type, project.id)), "workspace.uninstall-close-required", "Close all editors for this package before uninstalling") }
                val result = packs.uninstall(id)
                if (installed.manifest.projects.any { it.type == "template" }) editor.refreshSnapshot(player)
                reply(player, requestId, action, listing(player).apply { put("uninstalled", id); put("warnings", result.warnings); put("backups", result.backups.map(Path::toString)) })
            }
            "preview-open" -> {
                val selected = selection(args); val frame = args.get("frame")?.asDouble
                val filename = args.string("filename")
                if (filename.isBlank()) openCapture(player, packet, selected, frame)
                else async(player, packet, { synchronized(fileLock) { packs.previewExport(filename, ArcPackSelection(selected.type, selected.id), frame) } }) { bundled ->
                    openCapture(player, packet, selected, frame, bundled)
                }
            }
            "preview-restore" -> { restoreCapture(player); reply(player, requestId, action, mapOf("restored" to true)) }
            "preview-chunk" -> {
                previewChunk(player, args)
                reply(player, requestId, action, mapOf("index" to args.get("index").asInt))
            }
            "publish" -> {
                async(player, packet, {
                    val file = synchronized(fileLock) { publication(args) }
                    try { gateway.publish(file, ArcPackIO.inspect(file).id, args.required("token")) } finally { Files.deleteIfExists(file) }
                }) {
                    reply(player, requestId, action, it)
                }
            }
            else -> localizedError("workspace.action-unknown", "Unknown workspace action: $action", action)
        }
    }
    private fun selection(args: JsonObject): WorkspaceSelection = WorkspaceSelection(args.required("type"), args.required("id")).also {
        localizedRequire(it.type in setOf("menu", "template"), "workspace.project-type-invalid", "Unknown project type")
    }
    private fun isEditing(selection: WorkspaceSelection): Boolean = when (selection.type) {
        "menu" -> editor.isEditing(selection.id)
        else -> false
    }
    private fun selections(args: JsonObject): List<WorkspaceSelection> = args.getAsJsonArray("projects").map { selection(it.asJsonObject) }.also {
        localizedRequire(it.isNotEmpty() && it.size <= 512 && it.distinct().size == it.size, "workspace.project-selection-count", "Select between 1 and 512 different projects")
    }
    private fun exportFile(name: String): Path = packs.exportedFile(name)
    private fun <T> async(player: Player, packet: WorkspaceProtocol.Request, work: () -> T, complete: (T) -> Unit) {
        worker.execute {
            try {
                val result = work()
                if (!plugin.isEnabled || !player.isOnline) { if (result is Path) Files.deleteIfExists(result); return@execute }
                ArcScheduler.execute(plugin, player, Runnable {
                    try {
                        localizedRequire(player.hasPermission("arcmenu.admin"), "workspace.permission-revoked", "Administrator permission was revoked")
                        complete(result)
                    } catch (error: Exception) { failure(player, packet.requestId(), packet.action(), error) }
                })
            } catch (error: Exception) { if (plugin.isEnabled) ArcScheduler.execute(plugin, player, Runnable { failure(player, packet.requestId(), packet.action(), error) }) }
        }
    }
    private fun openCapture(player: Player, packet: WorkspaceProtocol.Request, selected: WorkspaceSelection, requestedFrame: Double?, bundled: ArcPackPreviewScene? = null) {
        val frame = 0.0
        val scene = bundled?.scene ?: projects.previewScene(selected)
        if (!captures.containsKey(player.uniqueId)) captures[player.uniqueId] = Capture(selected)
        else captures.getValue(player.uniqueId).project = selected
        val canvas = scene.canvas
        val verticalSpan = 2 * tan(Math.toRadians(35.0))
        val fitPixels = maxOf(canvas.width / (verticalSpan * 16.0 / 9.0), canvas.height / verticalSpan) / (canvas.distance * .96)
        val fitted = scene.copy(canvas = canvas.copy(pixelsPerBlock = fitPixels), backend = emptyList())
        localizedRequire(sessions.openPreview(player, fitted, MenuViewMode.FRONTEND_PREVIEW) > 0, "workspace.preview-open-failed", "Package preview could not be opened")
        fun ready(attempt: Int) {
            if (!player.isOnline || !captures.containsKey(player.uniqueId)) return
            if (!sessions.packagePreviewCameraReady(player)) {
                if (attempt >= 100) {
                    restoreCapture(player)
                    failure(player, packet.requestId(), packet.action(), localizedStateFailure("workspace.preview-camera-timeout", "Package preview camera timed out"))
                    return
                }
                ArcScheduler.later(plugin, player, Runnable { ready(attempt + 1) }, 1)
                return
            }
            val viewport = sessions.packagePreviewGeometry(player)
            reply(player, packet.requestId(), packet.action(), mapOf("width" to scene.canvas.width, "height" to scene.canvas.height,
                "pixelsPerBlock" to (viewport?.get("pixelsPerBlock") ?: fitted.canvas.pixelsPerBlock), "distance" to (viewport?.get("distance") ?: fitted.canvas.distance),
                "offsetX" to (viewport?.get("offsetX") ?: 0.0), "offsetY" to (viewport?.get("offsetY") ?: 0.0), "frame" to frame))
        }
        ArcScheduler.later(plugin, player, Runnable { ready(0) }, 2)
    }
    private fun restoreCapture(player: Player) {
        val capture = captures.remove(player.uniqueId) ?: return
        if (editor.contains(player)) { editor.restorePreview(player) }
        else sessions.close(player, true)
    }
    private fun previewDirectory(player: Player, filename: String): Path = previewDirectory(player.uniqueId, filename)
    private fun previewDirectory(uuid: UUID, filename: String): Path = root.resolve("packs/.previews").resolve(uuid.toString()).resolve(filename)
    private fun previewChunk(player: Player, args: JsonObject) {
        val filename = args.required("filename"); val archive = exportFile(filename)
        val name = args.required("name")
        val index = args.get("index").asInt; val count = args.get("count").asInt
        localizedRequire(index >= 0 && index < count && count in 1..512, "workspace.preview-chunk-invalid", "Invalid preview chunk index")
        val encoded = args.required("data"); localizedRequire(encoded.length <= 20_000, "workspace.preview-chunk-too-large", "Preview chunk is too large")
        val bytes = Base64.getDecoder().decode(encoded); localizedRequire(bytes.isNotEmpty(), "workspace.preview-chunk-empty", "Empty preview chunk")
        val key = "${player.uniqueId}/$filename/$name"
        if (index == 0) {
            val manifest = ArcPackIO.inspect(archive)
            localizedRequire(manifest.projects.any { name == "${it.type}-${it.id}.png" }, "workspace.preview-name-invalid", "Preview name must identify an exported project")
            val file = previewDirectory(player, filename).resolve(name)
            Files.createDirectories(file.parent); Files.write(file, byteArrayOf())
            uploads[key] = PreviewUpload(file, count)
        }
        val upload = uploads[key] ?: localizedError("workspace.preview-not-started", "Preview upload has not started")
        localizedRequire(upload.count == count && upload.next == index && upload.bytes + bytes.size <= 8L * 1024 * 1024, "workspace.preview-order-invalid", "Preview chunks must arrive in order and stay below 8 MB")
        Files.write(upload.file, bytes, StandardOpenOption.APPEND); upload.next++; upload.bytes += bytes.size
        if (upload.next == count) {
            try {
                ImageIO.createImageInputStream(upload.file.toFile()).use { input ->
                    val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: localizedError("workspace.preview-png-required", "Preview must be a PNG image")
                    try { reader.input = input; localizedRequire(reader.formatName.equals("png", true) && reader.getWidth(0) == 1280 && reader.getHeight(0) == 720, "workspace.preview-dimensions-invalid", "Package previews must be real 1280x720 PNG captures") }
                    finally { reader.dispose() }
                }
            } catch (error: Exception) { Files.deleteIfExists(upload.file); throw error }
            finally { uploads.remove(key) }
        }
    }
    private fun stagedPreviews(uuid: UUID, filename: String): Map<String, ByteArray> {
        val manifest = ArcPackIO.inspect(exportFile(filename))
        return manifest.projects.mapNotNull { project ->
            val name = "${project.type}-${project.id}.png"; val path = previewDirectory(uuid, filename).resolve(name)
            if (!Files.isRegularFile(path)) null else {
                localizedRequire(uploads["$uuid/$filename/$name"] == null, "workspace.preview-in-progress", "Preview upload is still in progress")
                localizedRequire(Files.size(path) <= 8L * 1024 * 1024, "workspace.preview-size-exceeded", "Package preview exceeds the size limit")
                "previews/$name" to Files.readAllBytes(path)
            }
        }.toMap()
    }
    private fun publication(args: JsonObject): Path {
        val filename = args.required("filename")
        packs.attachExportPreviews(filename, emptyMap(), ArcPackAuthor(args.required("authorUuid"), args.required("authorName")))
        val file = root.resolve("packs/.uploads/${UUID.randomUUID()}.arcpack")
        Files.createDirectories(file.parent); Files.copy(exportFile(filename), file)
        return file
    }
    fun download(sender: CommandSender, id: String, version: String = "") {
        requireMarketOperator(sender)
        localizedRequire(sender.hasPermission("arcmenu.admin"), "workspace.admin-required", "Administrator permission is required")
        notify(sender, "workspace.downloading", arrayOf(id))
        worker.execute {
            try {
                val file = gateway.download(id, version)
                val task = Runnable {
                    try {
                        localizedRequire(sender.hasPermission("arcmenu.admin"), "workspace.permission-revoked", "Administrator permission was revoked")
                        val result = synchronized(fileLock) { packs.install(file) }
                        notify(sender, "workspace.installed", arrayOf(result.pack.manifest.id))
                        if (resourcePending()) notify(sender, "workspace.resources-pending", emptyArray())
                    } catch (error: Exception) { notify(sender, "workspace.failed", arrayOf(language.errorText(sender, error, "workspace.operation-failed"))) }
                    finally { Files.deleteIfExists(file) }
                }
                if (sender is Player) ArcScheduler.execute(plugin, sender, task) else ArcScheduler.globalLater(plugin, task)
            } catch (error: Exception) {
                val task = Runnable { notify(sender, "workspace.failed", arrayOf(language.errorText(sender, error, "workspace.operation-failed"))) }
                if (sender is Player) ArcScheduler.execute(plugin, sender, task) else ArcScheduler.globalLater(plugin, task)
            }
        }
    }
    private fun reply(player: Player, id: Long, action: String, value: Any) = send(player, WorkspaceProtocol.Reply(id, true, action, json.toJson(value), ""))
    private fun failure(player: Player, id: Long, action: String, error: Exception) = send(player,
        WorkspaceProtocol.Reply(id, false, action, "{}", language.errorText(player, error, "workspace.operation-failed").take(1024)))
    private fun send(player: Player, packet: WorkspaceProtocol.Reply) {
        if (!player.isOnline || !plugin.isEnabled || !player.listeningPluginChannels.contains(WorkspaceProtocol.CHANNEL)) return
        EditorProtocol.frame(nextMessage.getAndIncrement(), WorkspaceProtocol.encode(packet)).forEach { player.sendPluginMessage(plugin, WorkspaceProtocol.CHANNEL, it) }
    }
    fun disconnect(player: Player) {
        captures.remove(player.uniqueId); rates.remove(player.uniqueId)
        uploads.keys.removeIf { it.startsWith("${player.uniqueId}/") }
    }
    override fun close() {
        worker.shutdownNow(); captures.clear(); rates.clear(); uploads.clear()
        plugin.server.messenger.unregisterIncomingPluginChannel(plugin, WorkspaceProtocol.CHANNEL, this)
        plugin.server.messenger.unregisterOutgoingPluginChannel(plugin, WorkspaceProtocol.CHANNEL)
    }
}
