package com.fentai.arcmenu.paper.resource

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

class GifFramesTest {
    @TempDir lateinit var directory: Path

    private val palette = intArrayOf(0, 0xFF0000, 0x0000FF, 0x00FF00, 0xFFFF00, 0xFF00FF, 0x00FFFF, 0xFFFFFF)
    private fun argb(index: Int): Int = if (index == 0) 0 else 0xFF000000.toInt() or palette[index]
    private data class Frame(val x: Int, val y: Int, val width: Int, val height: Int,
                             val pixels: IntArray, val disposal: Int = 0,
                             val transparent: Boolean = true, val interlaced: Boolean = false)

    /** Tiny indexed fixtures use a clear code before each literal, avoiding compression dependencies. */
    private fun fixture(name: String, width: Int, height: Int, frames: List<Frame>): Path {
        val bytes = ByteArrayOutputStream()
        fun word(value: Int) { bytes.write(value and 255); bytes.write((value ushr 8) and 255) }
        bytes.write("GIF89a".toByteArray(Charsets.US_ASCII))
        word(width); word(height)
        bytes.write(0xF2); bytes.write(7); bytes.write(0)
        palette.forEach { color -> bytes.write(color ushr 16 and 255); bytes.write(color ushr 8 and 255); bytes.write(color and 255) }
        for (frame in frames) {
            require(frame.pixels.size == frame.width * frame.height)
            bytes.write(byteArrayOf(0x21, 0xF9.toByte(), 4))
            bytes.write(frame.disposal shl 2 or if (frame.transparent) 1 else 0)
            word(1); bytes.write(0); bytes.write(0)
            bytes.write(0x2C); word(frame.x); word(frame.y); word(frame.width); word(frame.height)
            bytes.write(if (frame.interlaced) 0x40 else 0)
            bytes.write(3)
            val codes = buildList {
                for (pixel in frame.pixels) { add(8); add(pixel) }
                add(9)
            }
            val compressed = ByteArray((codes.size + 1) / 2)
            codes.forEachIndexed { index, code ->
                compressed[index / 2] = (compressed[index / 2].toInt() or (code shl ((index % 2) * 4))).toByte()
            }
            for (block in compressed.asList().chunked(255)) {
                bytes.write(block.size); bytes.write(block.toByteArray())
            }
            bytes.write(0)
        }
        bytes.write(0x3B)
        return directory.resolve(name).also { Files.write(it, bytes.toByteArray()) }
    }

    private fun decode(file: Path, pixelBudget: Long = GifFrames.MAX_PIXELS, frameBudget: Int = GifFrames.MAX_FRAMES): Pair<GifDimensions, List<IntArray>> {
        val frames = mutableListOf<IntArray>()
        val dimensions = GifFrames.decode(file, "/${file.fileName}", pixelBudget, frameBudget) { index, image ->
            assertEquals(frames.size, index)
            frames += pixels(image)
        }
        return dimensions to frames
    }
    private fun pixels(image: BufferedImage) = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)

    @Test fun `offset transparent frames compose and both disposal modes restore the authored canvas`() {
        val file = fixture("disposal.gif", 4, 3, listOf(
            Frame(0, 0, 4, 3, IntArray(12) { 1 }),
            Frame(1, 1, 2, 1, intArrayOf(2, 0), disposal = 3),
            Frame(2, 0, 1, 1, intArrayOf(3), disposal = 2),
            Frame(0, 2, 1, 1, intArrayOf(4)),
        ))
        val (dimensions, frames) = decode(file)
        assertEquals(GifDimensions(4, 3, 4), dimensions)
        val red = IntArray(12) { argb(1) }
        assertArrayEquals(red, frames[0])
        assertArrayEquals(red.copyOf().apply { this[5] = argb(2) }, frames[1])
        assertArrayEquals(red.copyOf().apply { this[2] = argb(3) }, frames[2])
        assertArrayEquals(red.copyOf().apply { this[2] = 0; this[8] = argb(4) }, frames[3])
        assertEquals(argb(1), frames[1][6], "Transparent source pixels must preserve the previous image")
    }

    @Test fun `opaque partial first frame and background disposal use the logical background color`() {
        val file = fixture("opaque.gif", 3, 2, listOf(
            Frame(1, 0, 1, 1, intArrayOf(1), disposal = 2, transparent = false),
            Frame(2, 1, 1, 1, intArrayOf(2), transparent = false),
        ))
        val frames = decode(file).second
        val white = IntArray(6) { argb(7) }
        assertArrayEquals(white.copyOf().apply { this[1] = argb(1) }, frames[0])
        assertArrayEquals(white.copyOf().apply { this[5] = argb(2) }, frames[1])
    }

    @Test fun `transparent first frame retains empty canvas outside its own offset bounds`() {
        val file = fixture("transparent.gif", 3, 2, listOf(Frame(1, 1, 2, 1, intArrayOf(3, 0))))
        val expected = IntArray(6).apply { this[4] = argb(3) }
        assertArrayEquals(expected, decode(file).second.single())
    }

    @Test fun `restore to previous remains correct for consecutive temporary frames`() {
        val file = fixture("previous.gif", 3, 1, listOf(
            Frame(0, 0, 3, 1, intArrayOf(1, 1, 1)),
            Frame(0, 0, 1, 1, intArrayOf(2), disposal = 3),
            Frame(1, 0, 1, 1, intArrayOf(3), disposal = 3),
            Frame(2, 0, 1, 1, intArrayOf(4)),
        ))
        val frames = decode(file).second
        assertArrayEquals(intArrayOf(argb(2), argb(1), argb(1)), frames[1])
        assertArrayEquals(intArrayOf(argb(1), argb(3), argb(1)), frames[2])
        assertArrayEquals(intArrayOf(argb(1), argb(1), argb(4)), frames[3])
    }

    @Test fun `interlaced images decode all four passes into normal row order`() {
        val rows = intArrayOf(1, 2, 3, 4, 5, 6, 1, 2)
        val passOrder = intArrayOf(0, 4, 2, 6, 1, 3, 5, 7)
        val stored = passOrder.flatMap { row -> List(3) { rows[row] } }.toIntArray()
        val file = fixture("interlaced.gif", 3, 8, listOf(Frame(0, 0, 3, 8, stored, interlaced = true)))
        val expected = rows.flatMap { row -> List(3) { argb(row) } }.toIntArray()
        assertArrayEquals(expected, decode(file).second.single())
    }

    @Test fun `invalid logical dimensions and frames outside the canvas are rejected`() {
        for ((width, height) in listOf(0 to 1, 1 to 0, 4097 to 1, 1 to 4097)) {
            val file = fixture("invalid-$width-$height.gif", width, height, listOf(Frame(0, 0, 1, 1, intArrayOf(1))))
            assertThrows<IllegalArgumentException> { decode(file) }
        }
        val outside = fixture("outside.gif", 3, 2, listOf(Frame(2, 1, 2, 1, intArrayOf(1, 2))))
        assertThrows<IllegalArgumentException> { decode(outside) }
        val invalid = directory.resolve("invalid.gif").also { Files.writeString(it, "not-an-animation") }
        assertThrows<IllegalArgumentException> { decode(invalid) }
    }

    @Test fun `frame and decoded pixel budgets reject before any frame reaches the consumer`() {
        val file = fixture("budget.gif", 4, 3, List(3) { Frame(0, 0, 1, 1, intArrayOf(it + 1)) })
        var consumed = 0
        assertThrows<IllegalArgumentException> { GifFrames.decode(file, "/budget.gif", frameBudget = 2) { _, _ -> consumed++ } }
        assertEquals(0, consumed)
        assertThrows<IllegalArgumentException> { GifFrames.decode(file, "/budget.gif", pixelBudget = 35) { _, _ -> consumed++ } }
        assertEquals(0, consumed, "Budget counts full composed canvases, including sparse partial frames")
        assertEquals(GifDimensions(4, 3, 3), decode(file, pixelBudget = 36, frameBudget = 3).first)
        val tooMany = fixture("too-many.gif", 1, 1, List(GifFrames.MAX_FRAMES + 1) { Frame(0, 0, 1, 1, intArrayOf(1)) })
        assertThrows<IllegalArgumentException> { GifFrames.decode(tooMany, "/too-many.gif") { _, _ -> consumed++ } }
        assertEquals(0, consumed)
    }
}
