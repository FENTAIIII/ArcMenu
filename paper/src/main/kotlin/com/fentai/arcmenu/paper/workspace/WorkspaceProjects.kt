package com.fentai.arcmenu.paper.workspace

import com.fentai.arcmenu.core.config.strictYaml

import com.fentai.arcmenu.paper.localization.localizedRequire
import com.fentai.arcmenu.paper.localization.localizedError
import com.fentai.arcmenu.core.config.MenuParser
import com.fentai.arcmenu.core.config.TemplateCatalog
import com.fentai.arcmenu.core.config.VisualTemplate
import com.fentai.arcmenu.core.config.templateScene
import com.fentai.arcmenu.core.model.Canvas
import com.fentai.arcmenu.core.model.GroupNode
import com.fentai.arcmenu.core.model.MenuDefinition
import com.fentai.arcmenu.core.model.MenuDocument
import com.fentai.arcmenu.core.model.VisualNode
import com.fentai.arcmenu.paper.pack.ArcPackImages
import com.google.gson.JsonObject
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import javax.imageio.ImageIO

data class WorkspaceSelection(val type: String, val id: String)

/** Saved author files are edited as one transaction; live editor drafts own their files. */
internal class WorkspaceProjects(
    dataFolder: Path,
    private val menus: () -> Map<String, MenuDocument>,
    private val refresh: () -> Unit,
    private val busy: (WorkspaceSelection) -> Boolean,
    private val templates: () -> Map<String, VisualTemplate> = { emptyMap() },
) {
    private val root = dataFolder.toAbsolutePath().normalize()
    private fun dump(value: Any): String = Yaml(DumperOptions().apply { defaultFlowStyle = DumperOptions.FlowStyle.BLOCK; indent = 2 }).dump(value)
    private fun unlocked(selection: WorkspaceSelection) {
        localizedRequire(selection.type == "template" || !busy(selection), "workspace.project-busy", "${selection.type} ${selection.id} is open in an editor; save and close it first", selection.type, selection.id)
    }
    private fun validateSelection(selection: WorkspaceSelection) {
        val id = if (selection.type == "template") Regex("[a-z0-9][a-z0-9_-]*") else Regex("[a-z0-9][a-z0-9_-]{0,63}")
        localizedRequire(selection.type in setOf("menu", "template") && id.matches(selection.id), "workspace.project-selection-invalid", "Invalid project selection")
    }
    fun sceneFile(selection: WorkspaceSelection): Path {
        validateSelection(selection)
        return safe(when (selection.type) {
            "menu" -> root.resolve("menus").resolve(menus()[selection.id]?.sourceName ?: localizedError("workspace.menu-missing", "Menu not found: ${selection.id}", selection.id))
            "template" -> root.resolve("templates").resolve(template(selection.id).sourceName).normalize().also {
                localizedRequire(it.startsWith(root.resolve("templates")), "workspace.template-path-outside", "Template path escapes the templates directory")
            }
            else -> localizedError("workspace.project-type-invalid", "Unsupported project type")
        })
    }
    private fun template(id: String): VisualTemplate = templates()[id]
        ?: localizedError("workspace.template-missing", "Template not found: $id", id)

    fun previewScene(selection: WorkspaceSelection): MenuDefinition {
        val source = Files.readString(sceneFile(selection))
        if (selection.type != "template") return MenuParser().parse(source).definition
        val saved = TemplateCatalog().parse(source, template(selection.id).sourceName)
        localizedRequire(saved.id == selection.id, "workspace.template-id-changed", "Saved template ID changed: ${selection.id}", selection.id)
        return templateScene(saved, ::imageSize)
    }
    private fun imageSize(source: String): Pair<Double, Double>? = runCatching {
        val directory = root.resolve("images")
        val file = directory.resolve(source.replace('\\', '/').trimStart('/')).normalize()
        if (!file.startsWith(directory) || !Files.isRegularFile(file) || !file.toRealPath().startsWith(directory.toRealPath())) return null
        ImageIO.createImageInputStream(file.toFile()).use { input ->
            ArcPackImages.dimensions(input)
        }
    }.getOrNull()
    private fun safe(file: Path): Path {
        val target = file.toAbsolutePath().normalize()
        localizedRequire(target.startsWith(root) && target != root, "workspace.project-path-outside", "Project path escapes the ArcMenu directory")
        var parent: Path? = target
        while (parent != null && !Files.exists(parent)) parent = parent.parent
        localizedRequire(parent != null && parent.toRealPath().startsWith(root.toRealPath()), "workspace.project-symlink-outside", "Project path escapes through a symbolic link")
        return target
    }
    fun list(): List<Map<String, Any>> = buildList {
        menus().values.forEach { document ->
            val scene = document.definition; val c = scene.canvas
            add(linkedMapOf("type" to "menu", "id" to scene.id, "path" to document.sourceName.replace('\\', '/'),
                "width" to c.width, "height" to c.height, "pixelsPerBlock" to c.pixelsPerBlock, "distance" to c.distance,
                "permission" to scene.permission, "mainMenu" to scene.mainMenu, "openCommands" to scene.openCommands,
                "editing" to busy(WorkspaceSelection("menu", scene.id))))
        }
        templates().values.forEach { template ->
            val canvas = Canvas()
            fun count(node: VisualNode): Int = 1 + if (node is GroupNode) node.children.sumOf(::count) else 0
            add(linkedMapOf("type" to "template", "id" to template.id, "path" to template.sourceName.replace('\\', '/'),
                "width" to canvas.width, "height" to canvas.height, "pixelsPerBlock" to canvas.pixelsPerBlock, "distance" to canvas.distance,
                "rootId" to template.root.properties.id, "nodeCount" to count(template.root), "previewOnly" to true, "editing" to false))
        }
    }.sortedWith(compareBy({ it["type"].toString() }, { it["path"].toString() }))

    fun create(args: JsonObject): WorkspaceSelection {
        val selected = WorkspaceSelection(args.required("type"), args.required("id"))
        validateSelection(selected)
        localizedRequire(selected.type == "menu", "workspace.project-type-invalid", "Only menu projects can be created here")
        localizedRequire(selected.id !in menus(), "workspace.project-id-exists", "Project ID already exists: ${selected.id}", selected.id)
        val folder = args.string("folder")
        localizedRequire(folder.length <= 512 && !folder.startsWith('/') && !folder.contains('\\') &&
            (folder.isEmpty() || folder.split('/').all { Regex("[a-zA-Z0-9_-]+").matches(it) && !it.startsWith("arcpack-") }),
            "workspace.project-folder-invalid", "Invalid project folder")
        val scene = linkedMapOf<String, Any>("schema-version" to 1, "id" to selected.id, "canvas" to canvas(args),
            "frontend" to emptyMap<String, Any>(), "backend" to emptyMap<String, Any>(), "permission" to args.string("permission"))
        val file = safe(root.resolve("menus").resolve(folder).resolve("${selected.id}.yml"))
        localizedRequire(!Files.exists(file), "workspace.project-path-exists", "Project path already exists")
        val completed = MenuParser().parse(dump(scene)).source
        transaction(mapOf(file to completed.toByteArray(Charsets.UTF_8)))
        return selected
    }

    private fun canvas(args: JsonObject): Map<String, Double> = linkedMapOf(
        "width" to args.number("width", 320.0), "height" to args.number("height", 180.0),
        "pixels-per-block" to args.number("pixelsPerBlock", 100.0), "distance" to args.number("distance", 3.0),
    ).also { values -> localizedRequire(values.values.all { it.isFinite() && it > 0 && it <= 100_000 }, "workspace.canvas-invalid", "Canvas settings must be positive finite values below 100000") }

    fun configure(args: JsonObject) {
        val selection = WorkspaceSelection(args.required("type"), args.required("id")); validateSelection(selection)
        localizedRequire(selection.type != "template", "workspace.template-configure-unsupported", "Templates do not have menu global settings")
        unlocked(selection)
        val file = sceneFile(selection)
        val original = Files.readString(file)
        val document = MenuParser().parse(original)
        val c = document.definition.canvas
        @Suppress("UNCHECKED_CAST") val scene = strictYaml().load<MutableMap<String, Any>>(original)
        val settings = JsonObject().apply {
            addProperty("width", c.width); addProperty("height", c.height); addProperty("pixelsPerBlock", c.pixelsPerBlock); addProperty("distance", c.distance)
            listOf("width", "height", "pixelsPerBlock", "distance").filter(args::has).forEach { add(it, args.get(it)) }
        }
        scene["canvas"] = canvas(settings)
        if (args.has("permission") && selection.type == "menu") scene["permission"] = args.required("permission")
        if (selection.type == "menu") {
            if (args.has("mainMenu")) scene["main-menu"] = args.get("mainMenu").asBoolean
            if (args.has("openCommands")) scene["open-commands"] = args.getAsJsonArray("openCommands").map { it.asString }
        }
        val source = dump(scene); val next = MenuParser().parse(source).definition
        val changes = linkedMapOf(file to source.toByteArray(Charsets.UTF_8))
        transaction(changes)
    }
    fun delete(selections: List<WorkspaceSelection>) {
        localizedRequire(selections.isNotEmpty() && selections.size <= 512 && selections.distinct().size == selections.size, "workspace.project-selection-count", "Select between 1 and 512 different projects")
        val changes = linkedMapOf<Path, ByteArray?>()
        selections.forEach { selection ->
            unlocked(selection)
            val file = sceneFile(selection)
            changes[file] = null
        }
        transaction(changes)
    }
    private fun transaction(changes: Map<Path, ByteArray?>) {
        val previous = changes.keys.associateWith { file -> safe(file); if (Files.exists(file)) Files.readAllBytes(file) else null }
        val backup = root.resolve("workspace-backups").resolve(UUID.randomUUID().toString())
        previous.forEach { (file, bytes) -> if (bytes != null) {
            val saved = backup.resolve(root.relativize(file)); Files.createDirectories(saved.parent); Files.write(saved, bytes)
        } }
        try {
            changes.forEach { (file, bytes) -> write(file, bytes) }
            refresh()
        } catch (error: Exception) {
            previous.forEach { (file, bytes) -> write(file, bytes) }
            runCatching(refresh).onFailure { error.addSuppressed(it) }
            throw error
        }
    }
    private fun write(file: Path, bytes: ByteArray?) {
        if (bytes == null) Files.deleteIfExists(file)
        else { Files.createDirectories(file.parent); Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING) }
    }
}

internal fun JsonObject.required(name: String): String = get(name)?.takeUnless { it.isJsonNull }?.asString ?: localizedError("workspace.field-missing", "Missing $name", name)
internal fun JsonObject.string(name: String, fallback: String = ""): String = if (has(name)) required(name) else fallback
internal fun JsonObject.number(name: String, fallback: Double): Double = if (has(name)) get(name).asDouble else fallback
