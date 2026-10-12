package com.fentai.arcmenu.core.pack

import com.fentai.arcmenu.core.config.strictYaml

import com.fentai.arcmenu.core.config.AnimationCatalog
import com.fentai.arcmenu.core.config.MenuParser
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ArcPackIO {
    private val idPattern = Regex("[a-z0-9][a-z0-9_-]{0,63}")
    fun validId(id: String): String = id.also { require(idPattern.matches(it)) { "Pack ID must use 1-64 lowercase letters, digits, underscores or hyphens" } }
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()

    fun validatePath(path: String): String = path.also {
        require(path.length in 1..240 && Regex("[a-zA-Z0-9_./-]+").matches(path)) { "Invalid package path: $path" }
        require(!path.startsWith('/') && path.split('/').none { part -> part.isBlank() || part == "." || part == ".." || part.endsWith('.') ||
            part.substringBefore('.').uppercase() in setOf("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9") }) { "Unsafe package path: $path" }
    }
    private fun allowed(path: String): Boolean = path in setOf("manifest.json", "config/animations.yml") ||
        Regex("menus/(?:[a-z0-9_.-]+/)*[a-z0-9_.-]+\\.yml").matches(path) ||
        Regex("templates/(?:[a-z0-9_.-]+/)*[a-z0-9_.-]+\\.yml").matches(path) ||
        Regex("images/(?:[a-z0-9_.-]+/)*[a-z0-9_.-]+\\.(?:png|gif)").matches(path) ||
        Regex("previews/(?:[a-z0-9_.-]+/)*[a-z0-9_.-]+\\.png").matches(path)

    fun read(archive: Path, limits: ArcPackLimits = ArcPackLimits()): ArcPackArchive {
        require(Files.isRegularFile(archive) && Files.size(archive) <= limits.totalBytes) { "Package archive is missing or exceeds the size limit" }
        val entries = linkedMapOf<String, ByteArray>(); val names = mutableSetOf<String>(); var total = 0L; var count = 0
        ZipFile(archive.toFile()).use { zip ->
            val iterator = zip.entries()
            while (iterator.hasMoreElements()) {
                val entry = iterator.nextElement(); require(++count <= limits.entries) { "Too many package entries" }
                val path = validatePath(entry.name.removeSuffix(if (entry.isDirectory) "/" else ""))
                require(path != "effects" && !path.startsWith("effects/")) { "Effect packages are not supported by ArcMenu Community" }
                require(names.add(path.lowercase())) { "Duplicate package entry: $path" }
                if (entry.isDirectory) { require(path.substringBefore('/') in setOf("menus", "templates", "images", "previews", "config")) { "Unsupported package directory: $path" }; continue }
                require(allowed(path)) { "Unsupported package entry: $path" }
                val bound = if (path == "manifest.json") limits.manifestBytes else limits.fileBytes
                require(entry.size < 0 || entry.size <= bound) { "Package file exceeds size limit: $path" }
                val bytes = zip.getInputStream(entry).use { input ->
                    val out = ByteArrayOutputStream(); val buffer = ByteArray(8192); var size = 0L
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        size += n; total += n
                        require(size <= bound && total <= limits.totalBytes) { "Package uncompressed data exceeds size limit" }
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                }
                entries[path] = bytes
            }
        }
        val manifest = parseManifest(utf8(entries.remove("manifest.json") ?: error("Package manifest.json is missing")))
        require(manifest.files.map { it.path }.toSet() == entries.keys) { "Package file table must match all archive files" }
        manifest.files.forEach { file -> val bytes = entries.getValue(file.path); require(bytes.size.toLong() == file.size && sha256(bytes) == file.sha256) { "Package file integrity check failed: ${file.path}" } }
        validateAnimations(manifest, entries)
        return ArcPackArchive(manifest, entries)
    }
    fun inspect(archive: Path): ArcPackManifest = read(archive).manifest

    fun write(archive: Path, manifest: ArcPackManifest, files: Map<String, ByteArray>, limits: ArcPackLimits = ArcPackLimits()) {
        val checked = parseManifest(serializeManifest(manifest))
        require(checked.files.map { it.path }.toSet() == files.keys && files.size + 1 <= limits.entries) { "Package file table does not match export files" }
        require(files.values.sumOf { it.size.toLong() } <= limits.totalBytes) { "Package data exceeds size limit" }
        checked.files.forEach { file -> val bytes = files.getValue(file.path); require(bytes.size <= limits.fileBytes && bytes.size.toLong() == file.size && sha256(bytes) == file.sha256) { "Invalid export file: ${file.path}" } }
        validateAnimations(checked, files)
        val manifestBytes = serializeManifest(checked).toByteArray(Charsets.UTF_8)
        require(manifestBytes.size <= limits.manifestBytes) { "Package manifest exceeds size limit" }
        require(files.values.sumOf { it.size.toLong() } + manifestBytes.size <= limits.totalBytes) { "Package data exceeds size limit" }
        Files.createDirectories(archive.toAbsolutePath().parent)
        val temp = archive.resolveSibling(".${archive.fileName}.${UUID.randomUUID()}.tmp")
        try {
            ZipOutputStream(Files.newOutputStream(temp)).use { zip ->
                (linkedMapOf("manifest.json" to manifestBytes) + files.toSortedMap()).forEach { (path, bytes) ->
                    zip.putNextEntry(ZipEntry(path).apply { time = 0 }); zip.write(bytes); zip.closeEntry()
                }
            }
            require(Files.size(temp) <= limits.totalBytes) { "Package archive exceeds size limit" }
            require(!Files.exists(archive)) { "Package export already exists: ${archive.fileName}" }
            Files.move(temp, archive)
        } finally { Files.deleteIfExists(temp) }
    }

    fun manifestMap(m: ArcPackManifest): Map<String, Any> = linkedMapOf(
        "format" to m.format, "formatVersion" to m.formatVersion, "id" to m.id, "version" to m.version,
        "title" to m.title, "description" to m.description, "author" to linkedMapOf("uuid" to m.author.uuid, "name" to m.author.name),
        "projects" to m.projects.map { linkedMapOf("type" to it.type, "id" to it.id, "path" to it.path) },
        "files" to m.files.map { linkedMapOf("path" to it.path, "sha256" to it.sha256, "size" to it.size) },
        "dependencies" to m.dependencies,
    ).apply { if (m.compatibility.isNotEmpty()) put("compatibility", m.compatibility) }
    fun serializeManifest(manifest: ArcPackManifest): String = ArcPackJson.encode(manifestMap(manifest)) + "\n"
    private fun validateAnimations(manifest: ArcPackManifest, files: Map<String, ByteArray>) {
        val bytes = files["config/animations.yml"] ?: return
        require(bytes.size <= 2_000_000) { "Package animation configuration exceeds 2 MB" }
        val source = utf8(bytes)
        val root = strictYaml().load<Any?>(source) as? Map<*, *>
            ?: error("Package animation configuration must be an object")
        val bindings = root["menus"] as? Map<*, *> ?: error("Package animation configuration requires menu bindings")
        val selected = manifest.projects.filter { it.type == "menu" }.associateBy { it.id }
        require(bindings.isNotEmpty() && bindings.keys.all { it is String && it in selected }) { "Package animations may bind only declared menu projects" }
        val menus = bindings.keys.associate { key ->
            val id = key as String; val project = selected.getValue(id)
            val document = MenuParser().parse(utf8(files.getValue(project.path)), project.path)
            require(document.definition.id == id) { "Package animated menu ID does not match its manifest" }
            id to document
        }
        AnimationCatalog().parse(source, menus, "config/animations.yml")
    }
    fun parseManifest(source: String): ArcPackManifest {
        fun obj(value: Any?, fields: Set<String>): Map<String, Any?> {
            require(value is Map<*, *> && value.keys.all { it is String && it in fields }) { "Invalid or unsupported package manifest fields" }
            @Suppress("UNCHECKED_CAST") return value as Map<String, Any?>
        }
        fun Map<String, Any?>.string(key: String, max: Int = 4096, empty: Boolean = false): String = (get(key) as? String ?: error("Package $key must be a string")).also { require(it.length <= max && (empty || it.isNotBlank()) && it.none { c -> c == '\u0000' }) { "Invalid package $key" } }
        fun Map<String, Any?>.list(key: String, max: Int): List<*> = (get(key) as? List<*> ?: error("Package $key must be an array")).also { require(it.size <= max) { "Too many package $key" } }
        val root = obj(ArcPackJson.decode(source), setOf("format", "formatVersion", "id", "version", "title", "description", "author", "projects", "files", "dependencies", "compatibility"))
        require(root["format"] == "arcpack" && root["formatVersion"] == 1L) { "Only ArcPack format version 1 is supported" }
        val id = validId(root.string("id", 64)); val version = root.string("version", 64)
        require(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}").matches(version)) { "Invalid package version" }
        val author = obj(root["author"], setOf("uuid", "name"))
        val uuid = author.string("uuid", 36); require(UUID.fromString(uuid).toString() == uuid.lowercase()) { "Invalid package author UUID" }
        val projects = root.list("projects", 1024).map { value ->
            val p = obj(value, setOf("type", "id", "path")); val type = p.string("type", 16); val projectId = p.string("id", 128); val path = validatePath(p.string("path", 240))
            require(type != "effect") { "Effect packages are not supported by ArcMenu Community" }
            require(type in setOf("menu", "template") && Regex("[a-z0-9][a-z0-9_-]{0,127}").matches(projectId)) { "Invalid package project" }
            require(when (type) {
                "menu" -> path.startsWith("menus/") && allowed(path)
                "template" -> path.startsWith("templates/") && allowed(path)
                else -> false
            }) { "Invalid package project path" }
            ArcPackProject(type, projectId, path)
        }
        require(projects.isNotEmpty() && projects.map { it.type to it.id }.distinct().size == projects.size && projects.map { it.path }.distinct().size == projects.size) { "Package projects must be nonempty and unique" }
        val files = root.list("files", 4095).map { value ->
            val f = obj(value, setOf("path", "sha256", "size")); val path = validatePath(f.string("path", 240)); val hash = f.string("sha256", 64); val size = f["size"]
            require(allowed(path) && path != "manifest.json" && Regex("[0-9a-f]{64}").matches(hash) && size is Long && size >= 0) { "Invalid package file record" }
            ArcPackFile(path, hash, size)
        }
        require(files.map { it.path }.distinct().size == files.size) { "Duplicate package file records" }
        val paths = files.map { it.path }.toSet()
        projects.forEach { p -> require(p.path in paths) { "Package project files are missing: ${p.id}" } }
        require(paths.filter { it.startsWith("menus/") }.all { path -> projects.any { it.type == "menu" && it.path == path } } &&
            paths.filter { it.startsWith("templates/") }.all { path -> projects.any { it.type == "template" && it.path == path } }) { "Package contains undeclared projects" }
        val dependencies = root.list("dependencies", 1024).map { require(it is String && it.isNotBlank() && it.length <= 2048 && it.none { c -> c.isISOControl() }) { "Invalid package dependency" }; it }
        require(dependencies.distinct().size == dependencies.size) { "Duplicate package dependencies" }
        val compatibility = if (root.containsKey("compatibility")) {
            val value = root["compatibility"]; require(value is Map<*, *> && value.size <= 32 && value.keys.all { it is String && it.length in 1..64 } && value.values.all { it is String && it.length <= 256 }) { "Invalid package compatibility" }
            @Suppress("UNCHECKED_CAST") (value as Map<String, String>)
        } else emptyMap()
        return ArcPackManifest(id, version, root.string("title", 256), root.string("description", 4096, true), ArcPackAuthor(uuid, author.string("name", 64)), projects, files, dependencies, compatibility)
    }
}
