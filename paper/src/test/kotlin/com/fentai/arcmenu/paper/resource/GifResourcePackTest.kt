package com.fentai.arcmenu.paper.resource

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import java.awt.Color

class GifResourcePackTest {
    @TempDir lateinit var directory: Path

    private fun gif(): ByteArray {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/images/example.gif")).use { it.readAllBytes() }
        Files.createDirectories(directory.resolve("images"))
        Files.write(directory.resolve("images/animated.gif"), bytes)
        return bytes
    }

    @Test fun `GIF frames become stable font glyphs only inside the resource ZIP`() {
        val original = gif()
        val service = ResourcePackService(directory, Logger.getAnonymousLogger())
        val result = service.rebuild()
        assertEquals(4, service.gifFrameCount("/animated.gif"))
        val frames = (0..3).map { requireNotNull(service.resolveGifFrame("/animated.gif", it.toDouble())) }
        assertEquals(4, frames.map { it.codepoint }.toSet().size)
        assertEquals(setOf("/animated.gif"), service.sources())
        assertTrue(frames.all { it.font == "arcmenu:gifs" })
        ZipFile(result.zip.toFile()).use { zip ->
            val font = zip.getInputStream(zip.getEntry("assets/arcmenu/font/gifs.json")).bufferedReader().readText()
            frames.forEachIndexed { index, asset ->
                val texture = "assets/arcmenu/textures/gifs/animated.gif/$index.png"
                assertNotNull(zip.getEntry(texture))
                assertTrue(font.contains("arcmenu:gifs/animated.gif/$index.png"))
                val bitmap = ImageIO.read(zip.getInputStream(zip.getEntry(texture)))
                assertEquals(asset.glyphBitmapWidth, bitmap.width)
                assertEquals(asset.glyphBitmapHeight, bitmap.height)
            }
            assertFalse(zip.entries().asSequence().any { it.name.endsWith(".gif") })
        }
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("images/animated.gif")))
        Files.walk(directory).use { files -> assertFalse(files.anyMatch { it.fileName.toString().matches(Regex("[0-9]+\\.png")) }) }
        val restarted = ResourcePackService(directory, Logger.getAnonymousLogger())
        restarted.rebuild()
        frames.forEachIndexed { index, asset -> assertEquals(asset, restarted.resolveGifFrame("/animated.gif", index.toDouble())) }

    }

    @Test fun `fractional speed holds frames and double speed skips alternate frames with looping`() {
        gif()
        val service = ResourcePackService(directory, Logger.getAnonymousLogger()).also { it.rebuild() }
        fun codes(speed: Double) = (0..5).map { service.resolveGifFrame("/animated.gif", it * speed)!!.codepoint }
        val frames = (0..3).map { service.resolveGifFrame("/animated.gif", it.toDouble())!!.codepoint }
        assertEquals(listOf(frames[0], frames[2], frames[0], frames[2], frames[0], frames[2]), codes(2.0))
        assertEquals(listOf(frames[0], frames[0], frames[1], frames[1], frames[2], frames[2]), codes(.5))
    }

    @Test fun `resource pack contains no shader resources`() {
        gif()
        val service = ResourcePackService(directory, Logger.getAnonymousLogger()).also { it.rebuild() }
        ZipFile(service.zipPath.toFile()).use { zip ->
            assertFalse(zip.entries().asSequence().any { it.name.contains("/shaders/") || it.name.endsWith(".fsh") || it.name.endsWith(".vsh") })
        }
    }
}
