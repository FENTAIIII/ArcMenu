package com.fentai.arcmenu.paper.resource

import java.util.concurrent.ConcurrentHashMap

import com.fentai.arcmenu.paper.localization.LanguageManager
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.logging.Logger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import com.fentai.arcmenu.paper.runtime.ScreenPlacement
import java.nio.file.attribute.FileTime
import kotlin.math.floor

data class ImageAsset(
    val source: String,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val codepoint: Int,
    val font: String = "arcmenu:images",
    val opaquePixelWidth: Int = pixelWidth,
    val glyphBitmapWidth: Int = pixelWidth,
    val glyphBitmapHeight: Int = pixelHeight,
) {
    val character: String get() = String(Character.toChars(codepoint))
    val glyphHeight: Double get() = FONT_HEIGHT.toDouble()
    val glyphWidth: Double get() = FONT_HEIGHT.toDouble() * glyphBitmapWidth / glyphBitmapHeight
    val glyphAdvance: Int get() = (0.5 + FONT_HEIGHT.toDouble() * opaquePixelWidth / glyphBitmapHeight).toInt() + 1

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
    @Volatile private var currentGifs: Map<String, List<ImageAsset>> = emptyMap()
    private val warnedMissingImages = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var currentNineSlices: Map<NineSliceRequest, NineSliceAsset> = emptyMap()
    @Volatile private var configuredNineSlices: Set<NineSliceRequest> = emptySet()

    init {
        Files.createDirectories(imagesDirectory)
        Files.createDirectories(rootDirectory.resolve("templates"))
        Files.createDirectories(generatedDirectory)
    }


    fun resolve(source: String): ImageAsset? = current[normalizeSource(source)]
    fun resolveGifFrame(source: String, position: Double): ImageAsset? {
        require(position.isFinite() && position >= 0) { "GIF frame position must be finite and nonnegative" }
        val frames = currentGifs[normalizeSource(source)] ?: return resolveMenuImage(source)
        return frames[(floor(position) % frames.size).toInt()]
    }
    fun gifFrameCount(source: String): Int = currentGifs[normalizeSource(source)]?.size ?: 0
    fun resolveMenuImage(source: String): ImageAsset? {
        val normalized = normalizeSource(source)
        val asset = current[normalized]
        if (asset == null && warnedMissingImages.add(normalized)) {
            logger.warning(language?.log("log.image-missing", normalized)
                ?: "ArcMenu: menu image $normalized is missing; its image nodes are hidden. Restore the file and run /arcmenu reload all.")
        }
        return asset
    }
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
            if (!relative.endsWith(".png", true) && !relative.endsWith(".gif", true)) return@filter false
            require(relative == relative.lowercase()) { "images/$relative: Resource paths must be lowercase" }
            require(file.toRealPath().startsWith(root)) { "images/$relative: Symbolic link escapes the images directory" }
            true
        }.sortedBy { imagesDirectory.relativize(it).toString().replace('\\', '/') }
        require(files.size <= MAX_IMAGES) { "Image count exceeds $MAX_IMAGES" }

        val retained = readIndex().toMutableMap()
        val used = retained.values.toMutableSet()
        require(used.size == retained.size) { "images.index contains duplicate code points" }
        val assets = linkedMapOf<String, ImageAsset>()
        val gifPlans = mutableListOf<GifPlan>()
        var decodedGifPixels = 0L
        var glyphs = files.count { it.fileName.toString().endsWith(".png") }
        var totalBytes = 0L
        files.forEach { file ->
            val relative = imagesDirectory.relativize(file).toString().replace('\\', '/')
            val source = normalizeSource("/$relative")
            val size = Files.size(file)
            require(size in 1..MAX_IMAGE_BYTES) { "$source: Image file size must be between 1 and $MAX_IMAGE_BYTES bytes" }
            totalBytes += size
            require(totalBytes <= MAX_TOTAL_BYTES) { "Total image size exceeds $MAX_TOTAL_BYTES bytes" }
            if (source.endsWith(".gif")) {
                val frames = mutableListOf<GifFrame>()
                val dimensions = GifFrames.decode(file, source, GifFrames.MAX_PIXELS - decodedGifPixels, MAX_IMAGES - glyphs) { index, image ->
                    val key = "@gif:$source:$index"
                    val codepoint = retained[key] ?: allocate(used).also { retained[key] = it; used += it }
                    val bitmap = fontBitmap(image)
                    val frameSource = "$source/$index.png"
                    val asset = ImageAsset(frameSource, image.width, image.height, codepoint, GIF_FONT,
                        actualGlyphWidth(bitmap), bitmap.width, bitmap.height)
                    val bytes = ByteArrayOutputStream().also { output ->
                        require(ImageIO.write(bitmap, "png", output)) { "$source: Cannot encode GIF frame PNG" }
                    }.toByteArray()
                    frames += GifFrame(asset, bytes)
                }
                decodedGifPixels += dimensions.width.toLong() * dimensions.height * dimensions.frames
                glyphs += dimensions.frames
                gifPlans += GifPlan(source, frames)
                assets[source] = frames.first().asset
            } else {
                val (width, height) = pngSize(file, source)
                val codepoint = retained[source] ?: allocate(used).also { retained[source] = it; used += it }
                assets[source] = ImageAsset(source, width, height, codepoint)
            }
        }
        val filesBySource = files.associateBy { file ->
            normalizeSource("/${imagesDirectory.relativize(file).toString().replace('\\', '/')}")
        }
        val slicePlans = requestedNineSlices.map { request ->
            slicePlan(request, assets, filesBySource, retained, used)
        }
        val tempDirectory = generatedDirectory.resolve(".resourcepack-${UUID.randomUUID()}")
        val tempZip = generatedDirectory.resolve(".arcmenu-${UUID.randomUUID()}.zip")
        try {
            writePack(tempDirectory, assets, files.filter { it.fileName.toString().endsWith(".png") }, slicePlans, retained, gifPlans)
            zip(tempDirectory, tempZip, gifPlans)
            moveReplace(tempZip, zipPath)
            replaceDirectory(tempDirectory, unpackedDirectory)
            writeIndex(retained)
            current = assets.toMap()
            currentGifs = gifPlans.associate { it.source to it.frames.map(GifFrame::asset) }
            warnedMissingImages.clear()
            currentNineSlices = slicePlans.associate { it.asset.request to it.asset }
            configuredNineSlices = requestedNineSlices
        } finally {
            Files.deleteIfExists(tempZip)
            deleteTree(tempDirectory)
        }
        return ResourceBuildResult(assets.size, totalBytes, zipPath)
    }

    private fun writePack(
        directory: Path,
        assets: MutableMap<String, ImageAsset>,
        files: List<Path>,
        slicePlans: List<SlicePlan>,
        retained: Map<String, Int>,
        gifPlans: List<GifPlan>,
    ) {
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("pack.mcmeta"), PACK_META, StandardCharsets.UTF_8)
        val textures = directory.resolve("assets/arcmenu/textures/images")
        files.forEach { file ->
            val relative = imagesDirectory.relativize(file)
            val destination = textures.resolve(relative.toString())
            Files.createDirectories(destination.parent)
            val source = normalizeSource("/${relative.toString().replace('\\', '/')}")
            val asset = assets.getValue(source)
            if (asset.pixelWidth > MAX_GLYPH_DIMENSION || asset.pixelHeight > MAX_GLYPH_DIMENSION) {
                val original = readImage(file, source)
                require(original.width == asset.pixelWidth && original.height == asset.pixelHeight) {
                    "$source: Decoded PNG dimensions do not match its header"
                }
                val bitmap = fontBitmap(original)
                require(ImageIO.write(bitmap, "png", destination.toFile())) { "$source: Cannot encode bitmap PNG" }
                assets[source] = asset.copy(
                    opaquePixelWidth = actualGlyphWidth(bitmap),
                    glyphBitmapWidth = bitmap.width,
                    glyphBitmapHeight = bitmap.height,
                )
            } else {
                Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        slicePlans.forEach { plan -> writeSlices(textures, plan) }
        val fontDirectory = directory.resolve("assets/arcmenu/font")
        Files.createDirectories(fontDirectory)
        val fontAssets = assets.values.filter { it.font != GIF_FONT } + gifPlans.flatMap { it.frames.map(GifFrame::asset) } + slicePlans.flatMap { it.asset.parts.values }
        val assetsByFont = fontAssets.groupBy(ImageAsset::font).toMutableMap()
        val activeCodepoints = fontAssets.mapTo(hashSetOf()) { it.codepoint }
        // Old entity text may still reference a deleted image. Reserve its codepoint and
        // render it as empty space in the new pack instead of a missing-glyph box.
        val retiredByFont = retained.entries.filter { it.value !in activeCodepoints }.groupBy {
            when {
                it.key.startsWith("@nine-slice:" ) -> TOOLTIP_SLICE_FONT
                it.key.startsWith("@gif:") -> GIF_FONT
                else -> "arcmenu:images"
            }
        }
        assetsByFont.putIfAbsent("arcmenu:images", emptyList())
        retiredByFont.keys.forEach { assetsByFont.putIfAbsent(it, emptyList()) }
        assetsByFont.forEach { (fontKey, groupedAssets) ->
            val ascent = if (fontKey == TOOLTIP_SLICE_FONT) TOOLTIP_SLICE_ASCENT else DEFAULT_IMAGE_ASCENT
            val providers = groupedAssets.map { asset ->
                val texture = asset.source.removePrefix("/")
                val folder = if (fontKey == GIF_FONT) "gifs" else "images"
                """{"type":"bitmap","file":"arcmenu:$folder/${json(texture)}","ascent":$ascent,"height":${ImageAsset.FONT_HEIGHT},"chars":["${json(asset.character)}"]}"""
            }.toMutableList()
            retiredByFont[fontKey]?.let { retired ->
                val advances = retired.joinToString(",") { "\"${json(String(Character.toChars(it.value)))}\":0" }
                providers += """{"type":"space","advances":{$advances}}"""
            }
            Files.writeString(
                fontDirectory.resolve("${fontKey.substringAfter(':')}.json"),
                "{\"providers\":[${providers.joinToString(",")}]}",
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
            "Tooltip skin background does not exist: ${request.background}"
        }
        val background = readImage(files.getValue(request.background), request.background)
        val frame = request.frame?.let { source ->
            val frameAsset = requireNotNull(assets[source]) { "Tooltip skin frame does not exist: $source" }
            require(frameAsset.pixelWidth == backgroundAsset.pixelWidth && frameAsset.pixelHeight == backgroundAsset.pixelHeight) {
                "Tooltip skin background and frame PNG dimensions must match"
            }
            readImage(files.getValue(source), source)
        }
        require(request.border * 2 < background.width && request.border * 2 < background.height) {
            "Tooltip skin border must be less than half the PNG width and height"
        }
        val textures = linkedMapOf<NineSlicePart, BufferedImage>()
        val parts = NineSlicePart.entries.associateWith { part ->
            val width = sliceLength(background.width, request.border, part.column)
            val height = sliceLength(background.height, request.border, part.row)
            val x = sliceStart(background.width, request.border, part.column)
            val y = sliceStart(background.height, request.border, part.row)
            val indexKey = "@nine-slice:${request.background}|${request.frame.orEmpty()}|${request.border}|${part.name}"
            val codepoint = retained[indexKey] ?: allocate(used).also { retained[indexKey] = it; used += it }
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                graphics.drawImage(background, 0, 0, width, height, x, y, x + width, y + height, null)
                frame?.let { graphics.drawImage(it, 0, 0, width, height, x, y, x + width, y + height, null) }
            } finally {
                graphics.dispose()
            }
            val bitmap = fontBitmap(image)
            textures[part] = bitmap
            ImageAsset(
                "/_generated/nine_slice/${codepoint.toString(16)}.png",
                width,
                height,
                codepoint,
                TOOLTIP_SLICE_FONT,
                if (bitmap === image) actualGlyphWidth(background, frame, x, y, width, height)
                else actualGlyphWidth(bitmap),
                bitmap.width,
                bitmap.height,
            )
        }
        return SlicePlan(NineSliceAsset(request, parts), textures)
    }

    private fun writeSlices(textures: Path, plan: SlicePlan) {
        plan.asset.parts.forEach { (part, asset) ->
            val destination = textures.resolve(asset.source.removePrefix("/"))
            Files.createDirectories(destination.parent)
            require(ImageIO.write(plan.textures.getValue(part), "png", destination.toFile())) { "Cannot encode tooltip skin PNG" }
        }
    }

    /** Bitmap font atlases accept raw glyph cells no larger than 256 pixels in either dimension. */
    private fun fontBitmap(image: BufferedImage): BufferedImage {
        val largest = maxOf(image.width, image.height)
        if (largest <= MAX_GLYPH_DIMENSION) return image
        val scale = MAX_GLYPH_DIMENSION.toDouble() / largest
        val width = (image.width * scale).roundToInt().coerceIn(1, MAX_GLYPH_DIMENSION)
        val height = (image.height * scale).roundToInt().coerceIn(1, MAX_GLYPH_DIMENSION)
        val bitmap = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = bitmap.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.drawImage(image, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return bitmap
    }

    private fun actualGlyphWidth(image: BufferedImage): Int =
        actualGlyphWidth(image, null, 0, 0, image.width, image.height)

    private fun readImage(file: Path, source: String): BufferedImage =
        requireNotNull(ImageIO.read(file.toFile())) { "$source: Cannot decode PNG pixels" }

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
                require(signature.contentEquals(PNG_SIGNATURE)) { "$source: Not a valid PNG file" }
                require(input.readInt() == 13) { "$source: PNG is missing the standard IHDR" }
                val type = ByteArray(4); input.readFully(type)
                require(String(type, StandardCharsets.US_ASCII) == "IHDR") { "$source: The first PNG chunk is not IHDR" }
                val width = input.readInt(); val height = input.readInt()
                require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) {
                    "$source: PNG dimensions must be between 1x1 and $MAX_DIMENSION x $MAX_DIMENSION"
                }
                return width to height
            }
        } catch (error: IOException) {
            throw IllegalArgumentException("$source: PNG header is truncated", error)
        }
    }

    private fun readIndex(): Map<String, Int> {
        if (!Files.exists(indexFile)) return emptyMap()
        return Files.readAllLines(indexFile, StandardCharsets.UTF_8).filter { it.isNotBlank() && !it.startsWith('#') }.associate { line ->
            val parts = line.split('=', limit = 2)
            require(parts.size == 2) { "Invalid images.index line format" }
            val source = if (parts[0].startsWith("@nine-slice:") || parts[0].startsWith("@gif:")) parts[0] else normalizeSource(parts[0])
            val codepoint = parts[1].toIntOrNull(16) ?: throw IllegalArgumentException("Invalid images.index code point: ${parts[1]}")
            require(codepoint in FIRST_CODEPOINT..LAST_CODEPOINT) { "images.index code point is outside the private-use area" }
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
        ?: throw IllegalArgumentException("Image code points are exhausted; remove unused historical images from generated/images.index")

    private fun zip(directory: Path, destination: Path, gifs: List<GifPlan>) {
        ZipOutputStream(Files.newOutputStream(destination)).use { output ->
            Files.walk(directory).use { stream ->
                stream.filter(Files::isRegularFile).sorted().forEach { file ->
                    val name = directory.relativize(file).toString().replace('\\', '/')
                    output.putNextEntry(ZipEntry(name).apply { time = 0L })
                    Files.copy(file, output)
                    output.closeEntry()
                }
            }
            // Sequence PNGs exist only in the ZIP; the authored images folder and unpacked pack stay compact.
            gifs.forEach { gif -> gif.frames.forEach { frame ->
                output.putNextEntry(ZipEntry("assets/arcmenu/textures/gifs/${frame.asset.source.removePrefix("/")}").apply { time = 0L })
                output.write(frame.png)
                output.closeEntry()
            } }
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
        require(path.normalize().startsWith(generatedDirectory.normalize())) { "Refusing to clean a path outside the generated directory" }
        Files.walk(path).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private fun normalizeSource(source: String): String {
        val value = source.replace('\\', '/')
        require(Regex("/[a-z0-9_./-]+\\.(png|gif)").matches(value) && !value.contains("//") &&
            value.split('/').none { it == "." || it == ".." }) { "Invalid virtual image path: $source" }
        return value
    }

    private fun normalize(request: NineSliceRequest): NineSliceRequest {
        require(request.border > 0) { "Tooltip skin border must be greater than 0" }
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
        const val GIF_FONT = "arcmenu:gifs"
        const val MAX_IMAGES = 4096
        const val MAX_DIMENSION = 4096
        const val MAX_GLYPH_DIMENSION = 256
        const val MAX_IMAGE_BYTES = 16L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 128L * 1024 * 1024
        const val FIRST_CODEPOINT = 0xE000
        const val LAST_CODEPOINT = 0xF8FF
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        const val PACK_META = """{"pack":{"description":{"text":"ArcMenu generated resources","color":"gray"},"pack_format":34,"supported_formats":{"min_inclusive":34,"max_inclusive":97},"min_format":[34,0],"max_format":[97,1]}}"""
    }

    private data class SlicePlan(
        val asset: NineSliceAsset,
        val textures: Map<NineSlicePart, BufferedImage>,
    )
    private data class GifFrame(val asset: ImageAsset, val png: ByteArray)
    private data class GifPlan(val source: String, val frames: List<GifFrame>)
}
