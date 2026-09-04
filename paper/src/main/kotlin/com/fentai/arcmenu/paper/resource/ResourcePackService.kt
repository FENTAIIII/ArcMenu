package com.fentai.arcmenu.paper.resource

import com.fentai.arcmenu.paper.localization.LanguageManager
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.logging.Logger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

data class ImageAsset(
    val source: String,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val codepoint: Int,
    val font: String = "arcmenu:images",
    val opaquePixelWidth: Int = pixelWidth,
) {
    val character: String get() = String(Character.toChars(codepoint))
    val glyphHeight: Double get() = FONT_HEIGHT.toDouble()
    val glyphWidth: Double get() = FONT_HEIGHT.toDouble() * pixelWidth / pixelHeight
    val glyphAdvance: Int get() = (0.5 + FONT_HEIGHT.toDouble() * opaquePixelWidth / pixelHeight).toInt() + 1

    companion object { const val FONT_HEIGHT = 8 }
}

data class ResourceBuildResult(val images: Int, val bytes: Long, val zip: Path)

enum class NineSlicePart(val column: Int, val row: Int) {
    TOP_LEFT(0, 0), TOP(1, 0), TOP_RIGHT(2, 0),
    LEFT(0, 1), CENTER(1, 1), RIGHT(2, 1),
    BOTTOM_LEFT(0, 2), BOTTOM(1, 2), BOTTOM_RIGHT(2, 2),
}

data class NineSliceRequest(val background: String, val frame: String?, val border: Int)
data class NineSliceAsset(val request: NineSliceRequest, val parts: Map<NineSlicePart, ImageAsset>)

/** Builds an isolated ArcMenu pack. CraftEngine merges the resulting zip through its cache event. */
class ResourcePackService(
    dataFolder: Path,
    private val logger: Logger,
    private val language: LanguageManager? = null,
) {
    // Bukkit may expose the plugin data folder as a relative path. CraftEngine resolves
    // external packs from worker threads and deliberately rejects a path that does not
    // identify a real file from its process working directory, so keep every published
    // resource path absolute from the start.
    private val rootDirectory = dataFolder.toAbsolutePath().normalize()
    private val imagesDirectory = rootDirectory.resolve("images")
    private val generatedDirectory = rootDirectory.resolve("generated")
    private val unpackedDirectory = generatedDirectory.resolve("resourcepack")
    private val indexFile = generatedDirectory.resolve("images.index")
    val zipPath: Path = generatedDirectory.resolve("arcmenu-resourcepack.zip")

    @Volatile private var current: Map<String, ImageAsset> = emptyMap()
    @Volatile private var currentNineSlices: Map<NineSliceRequest, NineSliceAsset> = emptyMap()
    @Volatile private var configuredNineSlices: Set<NineSliceRequest> = emptySet()

    init {
        Files.createDirectories(imagesDirectory)
        Files.createDirectories(rootDirectory.resolve("templates"))
        Files.createDirectories(generatedDirectory)
    }

    fun resolve(source: String): ImageAsset? = current[normalizeSource(source)]
    fun resolveNineSlice(request: NineSliceRequest): NineSliceAsset? = currentNineSlices[normalize(request)]
    fun hasNineSlices(requests: Set<NineSliceRequest>): Boolean = requests.map(::normalize).all(currentNineSlices::containsKey)
    fun imageCount(): Int = current.size
    fun sources(): Set<String> = current.keys

    @Synchronized
    fun rebuild(nineSlices: Set<NineSliceRequest>? = null): ResourceBuildResult {
        val requestedNineSlices = (nineSlices ?: configuredNineSlices).map(::normalize).toSet()
        val root = imagesDirectory.toRealPath()
        val files = Files.walk(imagesDirectory).use { stream ->
            stream.filter(Files::isRegularFile).toList()
        }.filter { file ->
            val relative = imagesDirectory.relativize(file).toString().replace('\\', '/')
            if (!relative.endsWith(".png", true)) return@filter false
            require(relative == relative.lowercase()) { "images/$relative：资源路径只能使用小写字母" }
            require(file.toRealPath().startsWith(root)) { "images/$relative：符号链接越过 images 目录" }
            true
        }.sortedBy { imagesDirectory.relativize(it).toString().replace('\\', '/') }
        require(files.size <= MAX_IMAGES) { "图片数量超过 $MAX_IMAGES" }

        val retained = readIndex().toMutableMap()
        val used = retained.values.toMutableSet()
        require(used.size == retained.size) { "images.index 存在重复码位" }
        val assets = linkedMapOf<String, ImageAsset>()
        var totalBytes = 0L
        files.forEach { file ->
            val relative = imagesDirectory.relativize(file).toString().replace('\\', '/')
            val source = normalizeSource("/$relative")
            val size = Files.size(file)
            require(size in 1..MAX_IMAGE_BYTES) { "$source：PNG 文件大小必须介于 1 和 $MAX_IMAGE_BYTES 字节" }
            totalBytes += size
            require(totalBytes <= MAX_TOTAL_BYTES) { "图片总大小超过 $MAX_TOTAL_BYTES 字节" }
            val (width, height) = pngSize(file, source)
            val codepoint = retained[source] ?: allocate(used).also { retained[source] = it; used += it }
            assets[source] = ImageAsset(source, width, height, codepoint)
        }
        val filesBySource = files.associateBy { file ->
            normalizeSource("/${imagesDirectory.relativize(file).toString().replace('\\', '/')}")
        }
        val slicePlans = requestedNineSlices.map { request ->
            slicePlan(request, assets, filesBySource, retained, used)
        }
        val removed = current.keys - assets.keys
        require(removed.isEmpty()) {
            "热构建不能删除已发布图片 ${removed.joinToString()}；请先移除菜单引用，再完整重启以生成不含它们的新包"
        }

        val tempDirectory = generatedDirectory.resolve(".resourcepack-${UUID.randomUUID()}")
        val tempZip = generatedDirectory.resolve(".arcmenu-${UUID.randomUUID()}.zip")
        try {
            writePack(tempDirectory, assets, files, slicePlans)
            zip(tempDirectory, tempZip)
            moveReplace(tempZip, zipPath)
            replaceDirectory(tempDirectory, unpackedDirectory)
            writeIndex(retained)
            current = assets.toMap()
            currentNineSlices = slicePlans.associate { it.asset.request to it.asset }
            configuredNineSlices = requestedNineSlices
        } finally {
            Files.deleteIfExists(tempZip)
            deleteTree(tempDirectory)
        }
        logger.info(language?.log("log.resource-built", assets.size, zipPath.fileName, totalBytes)
            ?: "ArcMenu M4: built ${assets.size} image assets into ${zipPath.fileName} ($totalBytes bytes).")
        return ResourceBuildResult(assets.size, totalBytes, zipPath)
    }

    private fun writePack(
        directory: Path,
        assets: Map<String, ImageAsset>,
        files: List<Path>,
        slicePlans: List<SlicePlan>,
    ) {
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("pack.mcmeta"), PACK_META, StandardCharsets.UTF_8)
        val textures = directory.resolve("assets/arcmenu/textures/images")
        files.forEach { file ->
            val relative = imagesDirectory.relativize(file)
            val destination = textures.resolve(relative.toString())
            Files.createDirectories(destination.parent)
            Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING)
        }
        slicePlans.forEach { plan -> writeSlices(textures, plan) }
        val fontDirectory = directory.resolve("assets/arcmenu/font")
        Files.createDirectories(fontDirectory)
        val fontAssets = assets.values + slicePlans.flatMap { it.asset.parts.values }
        val assetsByFont = fontAssets.groupBy(ImageAsset::font).toMutableMap()
        assetsByFont.putIfAbsent("arcmenu:images", emptyList())
        assetsByFont.forEach { (fontKey, groupedAssets) ->
            val ascent = if (fontKey == TOOLTIP_SLICE_FONT) TOOLTIP_SLICE_ASCENT else DEFAULT_IMAGE_ASCENT
            val providers = groupedAssets.joinToString(",") { asset ->
                val texture = asset.source.removePrefix("/")
                """{"type":"bitmap","file":"arcmenu:images/${json(texture)}","ascent":$ascent,"height":${ImageAsset.FONT_HEIGHT},"chars":["${json(asset.character)}"]}"""
            }
            Files.writeString(
                fontDirectory.resolve("${fontKey.substringAfter(':')}.json"),
                "{\"providers\":[$providers]}",
                StandardCharsets.UTF_8,
            )
        }
    }

    private fun slicePlan(
        request: NineSliceRequest,
        assets: Map<String, ImageAsset>,
        files: Map<String, Path>,
        retained: MutableMap<String, Int>,
        used: MutableSet<Int>,
    ): SlicePlan {
        val backgroundAsset = requireNotNull(assets[request.background]) {
            "tooltip skin background 不存在: ${request.background}"
        }
        val background = readImage(files.getValue(request.background), request.background)
        val frame = request.frame?.let { source ->
            val frameAsset = requireNotNull(assets[source]) { "tooltip skin frame 不存在: $source" }
            require(frameAsset.pixelWidth == backgroundAsset.pixelWidth && frameAsset.pixelHeight == backgroundAsset.pixelHeight) {
                "tooltip skin background 与 frame 的 PNG 尺寸必须相同"
            }
            readImage(files.getValue(source), source)
        }
        require(request.border * 2 < background.width && request.border * 2 < background.height) {
            "tooltip skin border 必须小于 PNG 宽高的一半"
        }
        val parts = NineSlicePart.entries.associateWith { part ->
            val width = sliceLength(background.width, request.border, part.column)
            val height = sliceLength(background.height, request.border, part.row)
            val x = sliceStart(background.width, request.border, part.column)
            val y = sliceStart(background.height, request.border, part.row)
            val indexKey = "@nine-slice:${request.background}|${request.frame.orEmpty()}|${request.border}|${part.name}"
            val codepoint = retained[indexKey] ?: allocate(used).also { retained[indexKey] = it; used += it }
            ImageAsset(
                "/_generated/nine_slice/${codepoint.toString(16)}.png",
                width,
                height,
                codepoint,
                TOOLTIP_SLICE_FONT,
                actualGlyphWidth(background, frame, x, y, width, height),
            )
        }
        return SlicePlan(NineSliceAsset(request, parts), background, frame)
    }

    private fun writeSlices(textures: Path, plan: SlicePlan) {
        plan.asset.parts.forEach { (part, asset) ->
            val x = sliceStart(plan.background.width, plan.asset.request.border, part.column)
            val y = sliceStart(plan.background.height, plan.asset.request.border, part.row)
            val image = BufferedImage(asset.pixelWidth, asset.pixelHeight, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                graphics.drawImage(
                    plan.background, 0, 0, asset.pixelWidth, asset.pixelHeight,
                    x, y, x + asset.pixelWidth, y + asset.pixelHeight, null,
                )
                plan.frame?.let { frame ->
                    graphics.drawImage(
                        frame, 0, 0, asset.pixelWidth, asset.pixelHeight,
                        x, y, x + asset.pixelWidth, y + asset.pixelHeight, null,
                    )
                }
            } finally {
                graphics.dispose()
            }
            val destination = textures.resolve(asset.source.removePrefix("/"))
            Files.createDirectories(destination.parent)
            require(ImageIO.write(image, "png", destination.toFile())) { "无法编码 tooltip skin PNG" }
        }
    }

    private fun readImage(file: Path, source: String): BufferedImage =
        requireNotNull(ImageIO.read(file.toFile())) { "$source：无法解码 PNG 像素" }

    private fun actualGlyphWidth(
        background: BufferedImage,
        frame: BufferedImage?,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int,
    ): Int {
        for (localX in width - 1 downTo 0) {
            for (localY in 0 until height) {
                val x = startX + localX
                val y = startY + localY
                if (background.getRGB(x, y) ushr 24 != 0 || (frame?.getRGB(x, y)?.ushr(24) ?: 0) != 0) {
                    return localX + 1
                }
            }
        }
        return 0
    }

    private fun sliceStart(total: Int, border: Int, index: Int): Int = when (index) {
        0 -> 0
        1 -> border
        else -> total - border
    }

    private fun sliceLength(total: Int, border: Int, index: Int): Int =
        if (index == 1) total - border * 2 else border

    private fun pngSize(file: Path, source: String): Pair<Int, Int> {
        try {
            DataInputStream(BufferedInputStream(Files.newInputStream(file))).use { input ->
                val signature = ByteArray(8); input.readFully(signature)
                require(signature.contentEquals(PNG_SIGNATURE)) { "$source：不是有效 PNG 文件" }
                require(input.readInt() == 13) { "$source：PNG 缺少标准 IHDR" }
                val type = ByteArray(4); input.readFully(type)
                require(String(type, StandardCharsets.US_ASCII) == "IHDR") { "$source：PNG 首块不是 IHDR" }
                val width = input.readInt(); val height = input.readInt()
                require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) {
                    "$source：PNG 尺寸必须介于 1×1 和 $MAX_DIMENSION×$MAX_DIMENSION"
                }
                return width to height
            }
        } catch (error: IOException) {
            throw IllegalArgumentException("$source：PNG 文件头被截断", error)
        }
    }

    private fun readIndex(): Map<String, Int> {
        if (!Files.exists(indexFile)) return emptyMap()
        return Files.readAllLines(indexFile, StandardCharsets.UTF_8).filter { it.isNotBlank() && !it.startsWith('#') }.associate { line ->
            val parts = line.split('=', limit = 2)
            require(parts.size == 2) { "images.index 行格式错误" }
            val source = if (parts[0].startsWith("@nine-slice:")) parts[0] else normalizeSource(parts[0])
            val codepoint = parts[1].toIntOrNull(16) ?: throw IllegalArgumentException("images.index 码位错误: ${parts[1]}")
            require(codepoint in FIRST_CODEPOINT..LAST_CODEPOINT) { "images.index 码位超出私用区" }
            source to codepoint
        }
    }

    private fun writeIndex(entries: Map<String, Int>) {
        val text = buildString {
            append("# ArcMenu stable image codepoints; do not edit while the server is running.\n")
            entries.toSortedMap().forEach { (source, codepoint) -> append(source).append('=').append(codepoint.toString(16)).append('\n') }
        }
        val temp = indexFile.resolveSibling(".${indexFile.fileName}-${UUID.randomUUID()}.tmp")
        Files.writeString(temp, text, StandardCharsets.UTF_8)
        moveReplace(temp, indexFile)
    }

    private fun allocate(used: Set<Int>): Int = (FIRST_CODEPOINT..LAST_CODEPOINT).firstOrNull { it !in used }
        ?: throw IllegalArgumentException("图片码位已耗尽；请清理 generated/images.index 中不再使用的历史图片")

    private fun zip(directory: Path, destination: Path) {
        ZipOutputStream(Files.newOutputStream(destination)).use { output ->
            Files.walk(directory).use { stream ->
                stream.filter(Files::isRegularFile).sorted().forEach { file ->
                    val name = directory.relativize(file).toString().replace('\\', '/')
                    output.putNextEntry(ZipEntry(name).apply { time = 0L })
                    Files.copy(file, output)
                    output.closeEntry()
                }
            }
        }
    }

    private fun replaceDirectory(source: Path, destination: Path) {
        deleteTree(destination)
        moveWithAccessRetry(source, destination)
    }

    private fun moveReplace(source: Path, destination: Path) {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            moveWithAccessRetry(source, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Windows scanners can briefly retain a handle after a generated file is closed. */
    private fun moveWithAccessRetry(source: Path, destination: Path, vararg options: StandardCopyOption) {
        repeat(5) { attempt ->
            try {
                Files.move(source, destination, *options)
                return
            } catch (error: AccessDeniedException) {
                if (attempt == 4) throw error
                try {
                    Thread.sleep(20L * (attempt + 1))
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("resource-pack publication interrupted", interrupted)
                }
            }
        }
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        require(path.normalize().startsWith(generatedDirectory.normalize())) { "拒绝清理 generated 目录之外的路径" }
        Files.walk(path).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private fun normalizeSource(source: String): String {
        val value = source.replace('\\', '/')
        require(Regex("/[a-z0-9_./-]+\\.png").matches(value) && !value.contains("//") &&
            value.split('/').none { it == "." || it == ".." }) { "无效图片虚拟路径: $source" }
        return value
    }

    private fun normalize(request: NineSliceRequest): NineSliceRequest {
        require(request.border > 0) { "tooltip skin border 必须大于 0" }
        return NineSliceRequest(
            normalizeSource(request.background),
            request.frame?.let(::normalizeSource),
            request.border,
        )
    }

    private fun json(value: String): String = buildString {
        value.forEach { character -> when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
        } }
    }

    private companion object {
        const val DEFAULT_IMAGE_ASCENT = 4
        const val TOOLTIP_SLICE_ASCENT = 2
        const val TOOLTIP_SLICE_FONT = "arcmenu:tooltip_slices"
        const val MAX_IMAGES = 4096
        const val MAX_DIMENSION = 4096
        const val MAX_IMAGE_BYTES = 16L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 128L * 1024 * 1024
        const val FIRST_CODEPOINT = 0xE000
        const val LAST_CODEPOINT = 0xF8FF
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        const val PACK_META = """{"pack":{"description":{"text":"ArcMenu generated resources","color":"gray"},"pack_format":34,"supported_formats":{"min_inclusive":34,"max_inclusive":84},"min_format":[34,0],"max_format":[84,0]}}"""
    }

    private data class SlicePlan(
        val asset: NineSliceAsset,
        val background: BufferedImage,
        val frame: BufferedImage?,
    )
}
