package com.fentai.arcmenu.paper.resource

import org.w3c.dom.Node
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

internal data class GifDimensions(val width: Int, val height: Int, val frames: Int)

/** Composites partial GIF images before encoding; frame delays are replaced by authored frames per tick. */
internal object GifFrames {
    const val MAX_FRAMES = 1024
    const val MAX_PIXELS = 16_777_216L

    fun decode(file: Path, source: String, pixelBudget: Long = MAX_PIXELS, frameBudget: Int = MAX_FRAMES,
               consume: (Int, BufferedImage) -> Unit): GifDimensions {
        val (width, height) = DataInputStream(BufferedInputStream(Files.newInputStream(file))).use { input ->
            val signature = ByteArray(6); input.readFully(signature)
            require(String(signature, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")) { "$source: Not a valid GIF file" }
            val w = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
            val h = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
            require(w in 1..4096 && h in 1..4096) { "$source: GIF dimensions must be between 1x1 and 4096x4096" }
            w to h
        }
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        try {
            Files.newInputStream(file).use { bytes ->
                ImageIO.createImageInputStream(bytes).use { input ->
                    reader.input = input
                    val count = reader.getNumImages(true)
                    require(count in 1..minOf(MAX_FRAMES, frameBudget)) { "$source: GIF frame count exceeds the available glyph budget" }
                    require(width.toLong() * height * count <= pixelBudget) { "$source: GIF decoded pixels exceed the resource budget" }
                    val stream = reader.streamMetadata.getAsTree("javax_imageio_gif_stream_1.0")
                    val screen = stream.child("LogicalScreenDescriptor")
                    require(screen?.int("logicalScreenWidth") == width && screen.int("logicalScreenHeight") == height) { "$source: GIF logical dimensions are inconsistent" }
                    val table = stream.child("GlobalColorTable")
                    val backgroundIndex = table?.int("backgroundColorIndex")
                    var background = 0
                    var entry = table?.firstChild
                    while (entry != null) {
                        if (entry.nodeName == "ColorTableEntry" && entry.int("index") == backgroundIndex) {
                            background = (255 shl 24) or (entry.int("red") shl 16) or (entry.int("green") shl 8) or entry.int("blue")
                        }
                        entry = entry.nextSibling
                    }
                    val canvas = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                    var previousDisposal = "none"
                    var previousBounds = intArrayOf(0, 0, width, height)
                    var previousBackground = background
                    var restore: BufferedImage? = null
                    for (index in 0 until count) {
                        val metadata = reader.getImageMetadata(index).getAsTree("javax_imageio_gif_image_1.0")
                        val control = metadata.child("GraphicControlExtension")
                        val transparent = control?.attr("transparentColorFlag") == "TRUE"
                        if (index == 0 && !transparent) fill(canvas, background, 0, 0, width, height)
                        when (previousDisposal) {
                            "restoreToBackgroundColor" -> fill(canvas, previousBackground, *previousBounds)
                            "restoreToPrevious" -> restore?.let { old -> canvas.createGraphics().let { g ->
                                try { g.composite = AlphaComposite.Src; g.drawImage(old, 0, 0, null) } finally { g.dispose() }
                            } }
                        }
                        val descriptor = requireNotNull(metadata.child("ImageDescriptor")) { "$source: GIF image descriptor is missing" }
                        val x = descriptor.int("imageLeftPosition"); val y = descriptor.int("imageTopPosition")
                        val w = descriptor.int("imageWidth"); val h = descriptor.int("imageHeight")
                        require(x >= 0 && y >= 0 && w > 0 && h > 0 && x.toLong() + w <= width && y.toLong() + h <= height) { "$source: GIF image lies outside its logical canvas" }
                        val disposal = control?.attr("disposalMethod") ?: "none"
                        restore = if (disposal == "restoreToPrevious") copy(canvas) else null
                        val frame = reader.read(index)
                        require(frame.width == w && frame.height == h) { "$source: GIF decoded dimensions do not match its descriptor" }
                        canvas.createGraphics().let { g ->
                            try { g.composite = AlphaComposite.SrcOver; g.drawImage(frame, x, y, null) } finally { g.dispose() }
                        }
                        consume(index, canvas)
                        previousDisposal = disposal
                        previousBounds = intArrayOf(x, y, w, h)
                        previousBackground = if (transparent) 0 else background
                    }
                    return GifDimensions(width, height, count)
                }
            }
        } finally { reader.dispose() }
    }

    private fun copy(image: BufferedImage): BufferedImage = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB).also { result ->
        result.createGraphics().let { g -> try { g.composite = AlphaComposite.Src; g.drawImage(image, 0, 0, null) } finally { g.dispose() } }
    }

    private fun fill(image: BufferedImage, argb: Int, vararg bounds: Int) {
        image.createGraphics().let { g ->
            try { g.composite = AlphaComposite.Src; g.color = Color(argb, true); g.fillRect(bounds[0], bounds[1], bounds[2], bounds[3]) }
            finally { g.dispose() }
        }
    }

    private fun Node.child(name: String): Node? {
        var node = firstChild
        while (node != null) { if (node.nodeName == name) return node; node = node.nextSibling }
        return null
    }
    private fun Node.attr(name: String): String = requireNotNull(attributes.getNamedItem(name)) { "Missing GIF metadata attribute $name" }.nodeValue
    private fun Node.int(name: String): Int = attr(name).toInt()
}
