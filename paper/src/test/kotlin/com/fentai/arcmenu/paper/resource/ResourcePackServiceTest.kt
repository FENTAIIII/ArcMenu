package com.fentai.arcmenu.paper.resource

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Logger
import java.util.zip.ZipFile
import javax.imageio.ImageIO

class ResourcePackServiceTest {
    @TempDir
    lateinit var directory: Path

    private fun png(path: Path, width: Int, height: Int) {
        Files.createDirectories(path.parent)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, 0xFF336699.toInt())
        ImageIO.write(image, "png", path.toFile())
    }

    @Test
    fun `pack contains private font and image with stable codepoint`() {
        png(directory.resolve("images/ui/logo.png"), 16, 8)
        val service = ResourcePackService(directory, Logger.getAnonymousLogger())
        val first = service.rebuild()
        val codepoint = service.resolve("/ui/logo.png")!!.codepoint
        assertEquals(16, service.resolve("/ui/logo.png")!!.pixelWidth)
        assertEquals(8, service.resolve("/ui/logo.png")!!.pixelHeight)
        assertTrue(Files.isDirectory(directory.resolve("templates")))

        ZipFile(first.zip.toFile()).use { zip ->
            assertNotNull(zip.getEntry("pack.mcmeta"))
            assertNotNull(zip.getEntry("assets/arcmenu/textures/images/ui/logo.png"))
            val font = zip.getInputStream(zip.getEntry("assets/arcmenu/font/images.json")).bufferedReader().readText()
            assertTrue(font.contains("arcmenu:images/ui/logo.png"))
            assertFalse(font.contains("minecraft:default"))
        }

        png(directory.resolve("images/ui/second.png"), 4, 4)
        service.rebuild()
        assertEquals(codepoint, service.resolve("/ui/logo.png")!!.codepoint)
        assertNotEquals(codepoint, service.resolve("/ui/second.png")!!.codepoint)

        val restarted = ResourcePackService(directory, Logger.getAnonymousLogger())
        restarted.rebuild()
        assertEquals(codepoint, restarted.resolve("/ui/logo.png")!!.codepoint)
    }

    @Test
    fun `published pack path is absolute when Bukkit data folder is relative`() {
        val relative = Path.of("target", "relative-resource-pack-${UUID.randomUUID()}")
        assertFalse(relative.isAbsolute)
        val service = ResourcePackService(relative, Logger.getAnonymousLogger())
        val result = service.rebuild()

        assertTrue(result.zip.isAbsolute)
        assertTrue(Files.isRegularFile(result.zip))
    }

    @Test
    fun `invalid png and uppercase path preserve previous valid zip`() {
        png(directory.resolve("images/valid.png"), 2, 2)
        val service = ResourcePackService(directory, Logger.getAnonymousLogger())
        service.rebuild()
        val previous = Files.readAllBytes(service.zipPath)

        Files.writeString(directory.resolve("images/broken.png"), "not png")
        assertThrows<IllegalArgumentException> { service.rebuild() }
        assertArrayEquals(previous, Files.readAllBytes(service.zipPath))
        Files.delete(directory.resolve("images/broken.png"))

        png(directory.resolve("images/Upper.png"), 2, 2)
        assertThrows<IllegalArgumentException> { service.rebuild() }
        assertArrayEquals(previous, Files.readAllBytes(service.zipPath))
    }

    @Test
    fun `hot build refuses image removal to protect active glyph references`() {
        val image = directory.resolve("images/used.png")
        png(image, 2, 2)
        val service = ResourcePackService(directory, Logger.getAnonymousLogger())
        service.rebuild()
        Files.delete(image)

        val error = assertThrows<IllegalArgumentException> { service.rebuild() }
        assertTrue(error.message.orEmpty().contains("热构建不能删除"))
        assertNotNull(service.resolve("/used.png"))
    }

    @Test
    fun `configured tooltip skin is composited into nine stable font glyphs`() {
        val background = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB).apply {
            for (y in 0 until height) for (x in 0 until width) setRGB(x, y, 0xFF123456.toInt())
        }
        val frame = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, 0xFFFF9900.toInt())
        }
        Files.createDirectories(directory.resolve("images/skin"))
        ImageIO.write(background, "png", directory.resolve("images/skin/background.png").toFile())
        ImageIO.write(frame, "png", directory.resolve("images/skin/frame.png").toFile())
        val request = NineSliceRequest("/skin/background.png", "/skin/frame.png", 2)
        val service = ResourcePackService(directory, Logger.getAnonymousLogger())

        val result = service.rebuild(setOf(request))
        val slices = requireNotNull(service.resolveNineSlice(request))

        assertEquals(9, slices.parts.size)
        assertEquals(2, slices.parts.getValue(NineSlicePart.TOP_LEFT).pixelWidth)
        assertEquals(6, slices.parts.getValue(NineSlicePart.CENTER).pixelWidth)
        assertEquals("arcmenu:tooltip_slices", slices.parts.getValue(NineSlicePart.CENTER).font)
        ZipFile(result.zip.toFile()).use { zip ->
            val corner = slices.parts.getValue(NineSlicePart.TOP_LEFT)
            val center = slices.parts.getValue(NineSlicePart.CENTER)
            fun entry(asset: ImageAsset) = zip.getEntry("assets/arcmenu/textures/images${asset.source}")
            assertNotNull(entry(corner))
            assertNotNull(entry(center))
            val cornerImage = ImageIO.read(zip.getInputStream(entry(corner)))
            val centerImage = ImageIO.read(zip.getInputStream(entry(center)))
            assertEquals(0xFFFF9900.toInt(), cornerImage.getRGB(0, 0))
            assertEquals(0xFF123456.toInt(), centerImage.getRGB(0, 0))
            val sliceFont = zip.getInputStream(zip.getEntry("assets/arcmenu/font/tooltip_slices.json"))
                .bufferedReader().readText()
            assertTrue(sliceFont.contains("\"ascent\":2"))
        }

        service.rebuild()
        assertEquals(slices.parts.mapValues { it.value.codepoint },
            service.resolveNineSlice(request)!!.parts.mapValues { it.value.codepoint })
    }
}
