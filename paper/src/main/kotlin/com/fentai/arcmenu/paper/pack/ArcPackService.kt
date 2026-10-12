package com.fentai.arcmenu.paper.pack

import com.fentai.arcmenu.paper.localization.localizedRequire
import com.fentai.arcmenu.paper.localization.localizedError
import com.fentai.arcmenu.core.config.MenuParser
import com.fentai.arcmenu.core.config.AnimationCatalog
import com.fentai.arcmenu.core.config.TemplateCatalog
import com.fentai.arcmenu.core.config.VisualTemplate
import com.fentai.arcmenu.core.config.templateScene
import com.fentai.arcmenu.core.model.MenuDefinition
import com.fentai.arcmenu.core.model.MenuDocument
import com.fentai.arcmenu.core.pack.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.attribute.FileTime

data class LocalArcPack(val filename: String, val manifest: ArcPackManifest, val size: Long, val modifiedAt: String,
    val previews: List<String>, val cover: String, val prepared: Boolean)
data class ArcPackPreviewScene(val scene: MenuDefinition, val frame: Double)

/** Saved-project packaging with an ownership journal; no Bukkit or editor runtime state is required. */
class ArcPackService @JvmOverloads constructor(
    dataFolder: Path,
    private val menuDocuments: () -> Collection<MenuDocument>,
    private val refresh: () -> Unit = {},
    private val templates: () -> Map<String, VisualTemplate> = { emptyMap() },
) {
    private val root = dataFolder.toAbsolutePath().normalize().let { if (Files.exists(it)) it.toRealPath() else it }
    private val parser = MenuParser()
    private val templateParser = TemplateCatalog()
    private data class OwnedFile(val path: String, val sha256: String, val size: Long)
    private data class Journal(val pack: InstalledArcPack, val files: List<OwnedFile>)
    private data class ExportStamp(val size: Long, val modified: FileTime)
    private val exportCatalog = mutableMapOf<Path, Pair<ExportStamp, LocalArcPack>>()

    @Synchronized fun inspect(archive: Path): ArcPackManifest = ArcPackIO.inspect(archive)
    @Synchronized fun list(): List<InstalledArcPack> {
        val dir = safe("packs/installed")
        if (!Files.exists(dir, NOFOLLOW_LINKS)) return emptyList()
        require(Files.isDirectory(dir, NOFOLLOW_LINKS)) { "Package registry must be a directory" }
        val entries = Files.list(dir).use { stream -> stream.filter { it.fileName.toString().endsWith(".json") }.sorted().toList() }
        require(entries.size <= 1024) { "Too many installed packages" }
        return entries.map { readJournal(it).pack }
    }

    /** Catalog only local archive metadata. Full file hashes are checked before every mutation or publication. */
    @Synchronized fun listExports(): List<LocalArcPack> {
        val dir = safe("packs")
        if (!Files.exists(dir, NOFOLLOW_LINKS)) return emptyList()
        require(Files.isDirectory(dir, NOFOLLOW_LINKS)) { "Package export location must be a directory" }
        val files = Files.list(dir).use { stream -> stream.filter { it.fileName.toString().endsWith(".arcpack") && Files.isRegularFile(it, NOFOLLOW_LINKS) }.sorted().toList() }
        require(files.size <= 1024) { "Too many local package archives" }
        exportCatalog.keys.retainAll(files.toSet())
        return files.mapNotNull { file -> runCatching {
            checkPath(file, "packs/${file.fileName}")
            val stamp = ExportStamp(Files.size(file), Files.getLastModifiedTime(file, NOFOLLOW_LINKS))
            require(stamp.size <= ArcPackLimits().totalBytes) { "Package archive exceeds the size limit" }
            exportCatalog[file]?.takeIf { it.first == stamp }?.second ?: run {
                val manifest = ZipFile(file.toFile()).use { zip ->
                    val entry = zip.getEntry("manifest.json") ?: error("Package manifest.json is missing")
                    require(entry.size in 0..ArcPackLimits().manifestBytes) { "Package manifest exceeds the size limit" }
                    val bytes = zip.getInputStream(entry).use { input -> input.readNBytes((ArcPackLimits().manifestBytes + 1).toInt()) }
                    require(bytes.size <= ArcPackLimits().manifestBytes) { "Package manifest exceeds the size limit" }
                    ArcPackIO.parseManifest(ArcPackIO.utf8(bytes))
                }
                val previews = previewNames(manifest).filter { name -> manifest.files.any { it.path == name } }
                LocalArcPack(file.fileName.toString(), manifest, stamp.size, stamp.modified.toInstant().toString(), previews, previews.firstOrNull().orEmpty(), previews.size == manifest.projects.size)
                    .also { exportCatalog[file] = stamp to it }
            }
        }.getOrNull() }
    }

    @Synchronized fun exportedFile(filename: String): Path {
        localizedRequire(Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,140}\\.(arcpack|zip)").matches(filename), "pack.file-name-invalid", "Invalid package file name")
        val file = safe("packs/$filename")
        localizedRequire(Files.isRegularFile(file, NOFOLLOW_LINKS), "pack.file-missing", "Package file is missing")
        return file
    }

    @Synchronized fun previewThumbnail(filename: String, name: String): ByteArray {
        val archive = ArcPackIO.read(exportedFile(filename))
        val path = "previews/${name.removePrefix("previews/")}"
        localizedRequire(path in previewNames(archive.manifest), "pack.preview-not-owned", "Preview does not belong to the selected package")
        val bytes = archive.files[path] ?: localizedError("pack.preview-not-prepared", "Package preview is not prepared")
        validatePreview(bytes)
        val image = ImageIO.read(bytes.inputStream()) ?: localizedError("pack.preview-decode-failed", "Package preview could not be decoded")
        val thumb = BufferedImage(320, 180, BufferedImage.TYPE_INT_ARGB)
        thumb.createGraphics().useGraphics { graphics ->
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.drawImage(image, 0, 0, 320, 180, null)
        }
        return ByteArrayOutputStream().also { ImageIO.write(thumb, "png", it) }.toByteArray()
    }

    @Synchronized fun deleteExports(filenames: List<String>): List<String> {
        localizedRequire(filenames.isNotEmpty() && filenames.size <= 512 && filenames.distinct().size == filenames.size, "pack.selection-count", "Select between 1 and 512 different packages")
        val files = filenames.map(::exportedFile)
        val backup = safe("workspace-backups/${UUID.randomUUID()}/packs")
        Files.createDirectories(backup)
        val moved = mutableListOf<Pair<Path, Path>>()
        try {
            files.forEach { source -> val target = backup.resolve(source.fileName); Files.move(source, target); moved += source to target; exportCatalog.remove(source) }
        } catch (error: Exception) {
            moved.asReversed().forEach { (source, target) -> runCatching { Files.move(target, source) }.onFailure(error::addSuppressed) }
            throw error
        }
        return moved.map { root.relativize(it.second).toString().replace('\\', '/') }
    }

    @Synchronized fun configureExport(filename: String, title: String, description: String): ArcPackManifest {
        val file = exportedFile(filename); val archive = ArcPackIO.read(file)
        val manifest = archive.manifest.copy(title = title.trim(), description = description.trim())
        requirePublishingMetadata(manifest)
        replaceExport(file, manifest, archive.files)
        return manifest
    }

    @Synchronized fun previewExport(filename: String, selected: ArcPackSelection, requestedFrame: Double?): ArcPackPreviewScene {
        val archive = ArcPackIO.read(exportedFile(filename))
        val project = archive.manifest.projects.firstOrNull { it.type == selected.type && it.id == selected.id } ?: localizedError("pack.project-not-owned", "Project is not in the selected package")
        archive.manifest.files.filter { it.path.startsWith("images/") }.forEach { bundled ->
            val source = safe(bundled.path)
            localizedRequire(Files.isRegularFile(source, NOFOLLOW_LINKS) && Files.size(source) == bundled.size && ArcPackIO.sha256(readFile(source, 32L * 1024 * 1024)) == bundled.sha256, "pack.image-changed", "Package image changed or is missing: ${bundled.path}. Re-export and capture this package before publishing", bundled.path)
        }
        if (project.type == "template") {
            val template = templateParser.parse(ArcPackIO.utf8(archive.files.getValue(project.path)), project.path)
            localizedRequire(template.id == project.id, "pack.template-id-invalid", "Package template ID does not match its manifest")
            val scene = templateScene(template) { source ->
                archive.files["images/" + source.removePrefix("/")]?.let { image ->
                    ImageIO.createImageInputStream(image.inputStream()).use { input ->
                        ArcPackImages.dimensions(input)
                    }
                }
            }
            return ArcPackPreviewScene(scene, 0.0)
        }
        val scenePath = if (project.type == "menu") project.path else "${project.path}/scene.yml"
        val scene = parser.parse(ArcPackIO.utf8(archive.files.getValue(scenePath)), scenePath).definition
        if (project.type == "menu") {
            localizedRequire(scene.id == project.id, "pack.menu-id-invalid", "Package menu ID does not match its manifest")
            return ArcPackPreviewScene(scene, 0.0)
        }
        error("Effect packages are not supported by ArcMenu Community")
    }

    @Synchronized fun attachExportPreviews(filename: String, previews: Map<String, ByteArray>, author: ArcPackAuthor? = null): ArcPackManifest {
        val file = exportedFile(filename); val archive = ArcPackIO.read(file)
        val expected = previewNames(archive.manifest)
        localizedRequire(previews.keys.all { it in expected }, "pack.preview-project-invalid", "Preview does not belong to a selected package project")
        val files = archive.files.filterKeys { !it.startsWith("previews/") }.toMutableMap()
        expected.forEach { name ->
            val bytes = previews[name] ?: archive.files[name] ?: localizedError("pack.preview-required", "Capture a 720p preview of every project before publishing")
            validatePreview(bytes)
            files[name] = bytes
        }
        val manifest = archive.manifest.copy(author = author ?: archive.manifest.author,
            files = files.map { (path, bytes) -> ArcPackFile(path, ArcPackIO.sha256(bytes), bytes.size.toLong()) })
        if (author != null) requirePublishingMetadata(manifest)
        replaceExport(file, manifest, files)
        return manifest
    }

    private fun previewNames(manifest: ArcPackManifest): List<String> = manifest.projects.map { "previews/${it.type}-${it.id}.png" }
    private fun requirePublishingMetadata(manifest: ArcPackManifest) {
        localizedRequire(manifest.title.isNotBlank() && manifest.title.length <= 256, "pack.title-required", "Enter a package title before publishing")
        localizedRequire(manifest.description.isNotBlank() && manifest.description.length <= 4096, "pack.description-required", "Enter a package description before publishing")
        ArcPackIO.parseManifest(ArcPackIO.serializeManifest(manifest))
    }
    private fun validatePreview(bytes: ByteArray) {
        localizedRequire(bytes.size in 1..8 * 1024 * 1024, "workspace.preview-size-exceeded", "Package preview exceeds the size limit")
        ImageIO.createImageInputStream(bytes.inputStream()).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: localizedError("pack.preview-png-required", "Package preview must be a PNG image")
            try {
                reader.input = input
                localizedRequire(reader.formatName.equals("png", true) && reader.getWidth(0) == 1280 && reader.getHeight(0) == 720, "workspace.preview-dimensions-invalid", "Package previews must be real 1280x720 PNG captures")
                localizedRequire(reader.read(0) != null, "pack.preview-decode-failed", "Package preview could not be decoded")
            } finally { reader.dispose() }
        }
    }
    private fun replaceExport(file: Path, manifest: ArcPackManifest, files: Map<String, ByteArray>) {
        val temp = file.resolveSibling(".${file.fileName}.${UUID.randomUUID()}.arcpack")
        try {
            ArcPackIO.write(temp, manifest, files)
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING) }
            exportCatalog.remove(file)
        } finally { Files.deleteIfExists(temp) }
    }

    private inline fun Graphics2D.useGraphics(block: (Graphics2D) -> Unit) {
        try { block(this) } finally { dispose() }
    }

    @Synchronized fun export(request: ArcPackExportRequest): ArcPackExportResult {
        ArcPackIO.validId(request.id)
        localizedRequire(request.projects.isNotEmpty() && request.projects.size <= 1024 && request.projects.distinct().size == request.projects.size, "pack.projects-required", "Select unique saved menu or template projects")
        val menus = menuDocuments().associateBy { it.definition.id }
        val savedTemplateCatalog = templates()
        val files = linkedMapOf<String, ByteArray>(); val projects = mutableListOf<ArcPackProject>()
        val savedMenus = linkedMapOf<String, MenuDocument>()
        val savedTemplates = mutableListOf<VisualTemplate>()
        var total = 0L
        fun add(path: String, data: ByteArray) {
            ArcPackIO.validatePath(path)
            val old = files[path]
            localizedRequire(old == null || old.contentEquals(data), "pack.file-conflict", "Conflicting exported package file: $path", path)
            if (old == null) {
                total += data.size
                localizedRequire(files.size < 4095 && total <= 128L * 1024 * 1024, "pack.export-limit", "Exported package exceeds the file count or total size limit")
                files[path] = data
            }
        }
        request.projects.forEach { selection ->
            when (selection.type) {
                "menu" -> {
                    val saved = menus[selection.id] ?: localizedError("pack.saved-menu-missing", "Saved menu does not exist: ${selection.id}", selection.id)
                    val name = Path.of(saved.sourceName)
                    val sourcePath = (if (name.isAbsolute) name else root.resolve("menus").resolve(name)).toAbsolutePath().normalize()
                    localizedRequire(sourcePath.startsWith(root.resolve("menus")), "pack.menu-source-outside", "Saved menu source must be inside the menus directory: ${selection.id}", selection.id)
                    val source = ArcPackIO.utf8(readFile(sourcePath, 2_000_000))
                    val parsed = parser.parse(source, sourcePath.toString())
                    localizedRequire(parsed.definition.id == selection.id, "pack.menu-id-changed", "Saved menu ID changed: ${selection.id}", selection.id)
                    val path = "menus/${selection.id}.yml"; add(path, source.toByteArray(Charsets.UTF_8)); projects += ArcPackProject("menu", selection.id, path)
                    savedMenus[selection.id] = parsed
                }
                "template" -> {
                    val saved = savedTemplateCatalog[selection.id] ?: localizedError("pack.saved-template-missing", "Saved template does not exist: ${selection.id}", selection.id)
                    val name = Path.of(saved.sourceName)
                    val sourcePath = (if (name.isAbsolute) name else root.resolve("templates").resolve(name)).toAbsolutePath().normalize()
                    localizedRequire(sourcePath.startsWith(root.resolve("templates")), "pack.template-source-outside", "Saved template source must be inside the templates directory: ${selection.id}", selection.id)
                    val source = ArcPackIO.utf8(readFile(sourcePath, 2_000_000))
                    val parsed = templateParser.parse(source, sourcePath.toString())
                    localizedRequire(parsed.id == selection.id, "pack.template-id-changed", "Saved template ID changed: ${selection.id}", selection.id)
                    val path = "templates/${selection.id}.yml"
                    add(path, source.toByteArray(Charsets.UTF_8)); projects += ArcPackProject("template", selection.id, path)
                    savedTemplates += parsed
                }
                else -> localizedError("pack.project-type-invalid", "Package project type must be menu or template")
            }
        }
        val animationSources = mutableListOf<Pair<String, String>>()
        val global = safe("animations.yml")
        if (Files.isRegularFile(global, NOFOLLOW_LINKS)) animationSources += "animations.yml" to ArcPackIO.utf8(readFile(global, 2_000_000))
        val fragments = safe("animations")
        if (Files.isDirectory(fragments, NOFOLLOW_LINKS)) Files.list(fragments).use { stream ->
            stream.filter { Regex("arcpack-[a-z0-9][a-z0-9_-]{0,63}\\.yml").matches(it.fileName.toString()) }.sorted().forEach { file ->
                animationSources += file.fileName.toString() to ArcPackIO.utf8(readFile(file, 2_000_000))
            }
        }
        val animation = ArcPackAnimations.collect(animationSources, savedMenus)
        if (animation != null) add("config/animations.yml", animation.source.toByteArray(Charsets.UTF_8))
        val refs = ArcPackReferences(savedMenus.keys, request.allowCe, animation?.tracks.orEmpty())
        savedMenus.values.forEach { refs.scene(it.definition) }
        savedTemplates.forEach(refs::template)
        refs.images.forEach { source -> add("images/$source", readFile(safe("images/$source"), (if (source.endsWith(".gif")) 16L else 32L) * 1024 * 1024)) }
        val dependencies = (request.dependencies + refs.dependencies).distinct().sorted()
        val manifest = ArcPackManifest(request.id, request.version, request.title, request.description, request.author, projects,
            files.map { (path, bytes) -> ArcPackFile(path, ArcPackIO.sha256(bytes), bytes.size.toLong()) }.sortedBy { it.path }, dependencies, request.compatibility)
        // Require the same basic listing metadata at packaging and publication.
        requirePublishingMetadata(manifest)
        val archive = safe("packs/${request.id}-${request.version}.arcpack")
        ArcPackIO.write(archive, manifest, files)
        return ArcPackExportResult(archive, manifest, warnings(dependencies))
    }

    @Synchronized fun install(archive: Path): ArcPackInstallResult {
        val pack = ArcPackIO.read(archive); val id = pack.manifest.id; val namespace = "arcpack-$id"
        val journalPath = safe("packs/installed/$id.json")
        localizedRequire(!Files.exists(journalPath, NOFOLLOW_LINKS), "pack.already-installed", "Package is already installed: $id", id)
        val menuIds = menuDocuments().map { it.definition.id }.toSet()
        val templateIds = templates().keys
        pack.manifest.projects.forEach { project ->
            val existing = when (project.type) { "menu" -> menuIds; "template" -> templateIds; else -> error("Effect packages are not supported by ArcMenu Community") }
            localizedRequire(project.id !in existing, "pack.project-id-exists", "Package ${project.type} ID already exists: ${project.id}", project.type, project.id)
        }
        val output = linkedMapOf<String, ByteArray>(); val included = pack.files.keys
        pack.files.forEach { (source, data) ->
            if (source.startsWith("previews/")) return@forEach
            val target = installedPath(source, namespace)
            val rewritten = when {
                source.startsWith("menus/") || source.startsWith("templates/") -> ArcPackSources.scene(ArcPackIO.utf8(data), namespace, included).toByteArray(Charsets.UTF_8)
                else -> data
            }
            val path = safe(target); localizedRequire(!Files.exists(path, NOFOLLOW_LINKS), "pack.destination-exists", "Package destination already exists: $target", target)
            output[target] = rewritten
        }
        localizedRequire(output.values.sumOf { it.size.toLong() } <= 128L * 1024 * 1024, "pack.install-size-limit", "Installed package exceeds the transaction size limit")
        validateProjects(pack.manifest, output, namespace)
        val now = Instant.now().toString()
        val journal = Journal(InstalledArcPack(pack.manifest, now, output.keys.sorted()), output.map { (path, bytes) -> OwnedFile(path, ArcPackIO.sha256(bytes), bytes.size.toLong()) })
        val journalBytes = serializeJournal(journal).toByteArray(Charsets.UTF_8)
        val created = mutableListOf<Path>()
        try {
            output.forEach { (path, bytes) -> val target = safe(path); writeNew(target, bytes); created.add(target) }
            writeNew(journalPath, journalBytes); created.add(journalPath)
            refresh()
        } catch (error: Exception) {
            created.asReversed().forEach { path -> runCatching { Files.deleteIfExists(path); removeEmptyParents(path.parent) }.onFailure(error::addSuppressed) }
            runCatching(refresh).onFailure(error::addSuppressed)
            throw error
        }
        return ArcPackInstallResult(journal.pack, warnings(pack.manifest.dependencies))
    }

    @Synchronized fun uninstall(id: String): ArcPackUninstallResult {
        ArcPackIO.validId(id)
        val journalPath = safe("packs/installed/$id.json")
        localizedRequire(Files.isRegularFile(journalPath, NOFOLLOW_LINKS), "workspace.pack-not-installed", "Package is not installed: $id", id)
        val journalBytes = readFile(journalPath, 2_000_000); val journal = readJournal(journalPath)
        val current = linkedMapOf<String, ByteArray>(); val modified = linkedMapOf<String, ByteArray>()
        var total = 0L
        journal.files.forEach { owned ->
            val path = safe(owned.path)
            if (!Files.exists(path, NOFOLLOW_LINKS)) return@forEach
            val data = readFile(path, 32L * 1024 * 1024); total += data.size
            localizedRequire(total <= 128L * 1024 * 1024, "pack.uninstall-size-limit", "Installed package data exceeds the uninstall transaction limit")
            current[owned.path] = data
            if (data.size.toLong() != owned.size || ArcPackIO.sha256(data) != owned.sha256) modified[owned.path] = data
        }
        val backupRoot = "packs/backups/$id-${UUID.randomUUID()}"
        val backups = mutableListOf<Path>(); val removed = mutableListOf<String>()
        try {
            modified.forEach { (path, data) -> val target = safe("$backupRoot/$path"); writeNew(target, data); backups.add(target) }
            current.forEach { (path, _) -> Files.delete(safe(path)); removed += path }
            Files.delete(journalPath)
            refresh()
        } catch (error: Exception) {
            removed.forEach { path -> runCatching { writeNew(safe(path), current.getValue(path)) }.onFailure(error::addSuppressed) }
            if (!Files.exists(journalPath, NOFOLLOW_LINKS)) runCatching { writeNew(journalPath, journalBytes) }.onFailure(error::addSuppressed)
            // Backups remain recoverable even if the runtime reload rejects this uninstall.
            runCatching(refresh).onFailure(error::addSuppressed)
            throw error
        }
        current.keys.forEach { removeEmptyParents(safe(it).parent) }
        removeEmptyParents(journalPath.parent)
        return ArcPackUninstallResult(id, removed.size, backups, if (backups.isEmpty()) emptyList() else listOf("Modified package files were preserved under $backupRoot"))
    }

    private fun validateProjects(manifest: ArcPackManifest, files: Map<String, ByteArray>, namespace: String) {
        val menus = linkedMapOf<String, MenuDocument>()
        manifest.projects.forEach { project ->
            if (project.type == "menu") {
                val path = "menus/$namespace/" + project.path.removePrefix("menus/")
                val document = parser.parse(ArcPackIO.utf8(files.getValue(path)), path)
                localizedRequire(document.definition.id == project.id, "pack.menu-manifest-id-invalid", "Package menu ID does not match its manifest: ${project.id}", project.id)
                menus[project.id] = document
            } else if (project.type == "template") {
                val path = "templates/$namespace/" + project.path.removePrefix("templates/")
                val template = templateParser.parse(ArcPackIO.utf8(files.getValue(path)), path)
                localizedRequire(template.id == project.id, "pack.template-manifest-id-invalid", "Package template ID does not match its manifest: ${project.id}", project.id)
            }
        }
        files["animations/$namespace.yml"]?.let { bytes ->
            localizedRequire(bytes.size <= 2_000_000, "pack.animation-too-large", "Package animation configuration exceeds 2 MB")
            val source = ArcPackIO.utf8(bytes); val bound = (ArcPackSources.read(source)["menus"] as Map<*, *>).keys
            AnimationCatalog().parse(source, menus.filterKeys { it in bound }, "animations/$namespace.yml")
        }
    }
    private fun installedPath(source: String, namespace: String): String = when {
        source == "config/animations.yml" -> "animations/$namespace.yml"
        source.startsWith("menus/") -> "menus/$namespace/" + source.removePrefix("menus/")
        source.startsWith("templates/") -> "templates/$namespace/" + source.removePrefix("templates/")
        source.startsWith("images/") -> "images/$namespace/" + source.removePrefix("images/")
        else -> localizedError("pack.install-path-unsupported", "Unsupported package installation path")
    }
    private fun warnings(dependencies: List<String>): List<String> = dependencies.map { "External dependency is not bundled: $it" }
    private fun safe(relative: String): Path {
        ArcPackIO.validatePath(relative)
        val path = root.resolve(relative).normalize(); localizedRequire(path.startsWith(root) && path != root, "pack.path-outside", "Package path escapes the data directory")
        return checkPath(path, relative)
    }
    private fun checkPath(path: Path, label: String): Path {
        var parent: Path? = path
        while (parent != null && parent != root) {
            localizedRequire(!Files.isSymbolicLink(parent), "pack.symlink-unsupported", "Package paths must not traverse symbolic links: $label", label)
            if (Files.exists(parent, NOFOLLOW_LINKS)) localizedRequire(parent.toRealPath().startsWith(root), "pack.path-label-outside", "Package path escapes the data directory: $label", label)
            parent = parent.parent
        }
        return path
    }
    private fun readFile(path: Path, limit: Long): ByteArray {
        localizedRequire(path.toAbsolutePath().normalize().startsWith(root), "pack.source-outside", "Package source escapes the data directory")
        val relative = root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/')
        val checked = checkPath(path.toAbsolutePath().normalize(), relative)
        localizedRequire(Files.isRegularFile(checked, NOFOLLOW_LINKS) && Files.size(checked) <= limit, "pack.source-unavailable", "Package source is missing, unsafe, or exceeds the size limit: $relative", relative)
        return Files.newInputStream(checked).use { input ->
            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192); var size = 0L
            while (true) {
                val n = input.read(buffer); if (n < 0) break
                size += n; localizedRequire(size <= limit, "pack.source-too-large", "Package source exceeds the size limit: $relative", relative); output.write(buffer, 0, n)
            }
            output.toByteArray()
        }
    }
    private fun writeNew(path: Path, bytes: ByteArray) {
        Files.createDirectories(path.parent)
        val temp = path.resolveSibling(".${path.fileName}.${UUID.randomUUID()}.tmp")
        try {
            Files.write(temp, bytes)
            localizedRequire(!Files.exists(path, NOFOLLOW_LINKS), "pack.destination-relative-exists", "Package destination already exists: ${root.relativize(path)}", root.relativize(path))
            // A normal no-replace rename preserves the conflict check on every filesystem.
            Files.move(temp, path)
        } finally { Files.deleteIfExists(temp) }
    }
    private fun removeEmptyParents(initial: Path) {
        var path = initial
        while (path.startsWith(root) && path != root && path != root.resolve("packs") && path != root.resolve("menus") && path != root.resolve("templates") && path != root.resolve("images") && path != root.resolve("animations")) {
            if (!Files.isDirectory(path, NOFOLLOW_LINKS) || Files.list(path).use { it.findAny().isPresent }) return
            Files.delete(path); path = path.parent
        }
    }
    private fun serializeJournal(j: Journal): String = ArcPackJson.encode(linkedMapOf(
        "manifest" to ArcPackIO.manifestMap(j.pack.manifest), "installedAt" to j.pack.installedAt,
        "files" to j.files.map { linkedMapOf("path" to it.path, "sha256" to it.sha256, "size" to it.size) },
    )) + "\n"
    private fun readJournal(file: Path): Journal {
        @Suppress("UNCHECKED_CAST") val value = ArcPackJson.decode(ArcPackIO.utf8(readFile(file, 2_000_000))) as? Map<String, Any?> ?: error("Invalid package ownership journal")
        require(value.keys == setOf("manifest", "installedAt", "files")) { "Invalid package ownership journal fields" }
        val manifest = ArcPackIO.parseManifest(ArcPackJson.encode(value["manifest"]))
        require(file.fileName.toString() == "${manifest.id}.json") { "Package journal ID does not match its filename" }
        val installed = value["installedAt"] as? String ?: error("Invalid package installation date"); Instant.parse(installed)
        val entries = value["files"] as? List<*> ?: error("Invalid package ownership files"); require(entries.size <= 4095) { "Too many package ownership files" }
        val namespace = "arcpack-${manifest.id}"
        val owned = entries.map { record ->
            val data = record as? Map<*, *> ?: error("Invalid package ownership record")
            require(data.keys == setOf("path", "sha256", "size")) { "Invalid package ownership record fields" }
            val path = data["path"] as? String ?: error("Invalid package ownership path"); ArcPackIO.validatePath(path)
            require(path.startsWith("menus/$namespace/") || path.startsWith("templates/$namespace/") || path.startsWith("images/$namespace/") || path == "animations/$namespace.yml") { "Package journal claims an unowned path" }
            val hash = data["sha256"] as? String ?: error("Invalid package ownership hash"); val size = data["size"]
            require(Regex("[a-f0-9]{64}").matches(hash) && size is Long && size in 0..(32L * 1024 * 1024)) { "Invalid package ownership metadata" }
            OwnedFile(path, hash, size)
        }
        require(owned.map { it.path }.distinct().size == owned.size) { "Duplicate package ownership paths" }
        val expected = manifest.files.filterNot { it.path.startsWith("previews/") }.map { installedPath(it.path, namespace) }.toSet()
        require(owned.map { it.path }.toSet() == expected) { "Package journal does not match its manifest ownership" }
        return Journal(InstalledArcPack(manifest, installed, owned.map { it.path }.sorted()), owned)
    }
}
