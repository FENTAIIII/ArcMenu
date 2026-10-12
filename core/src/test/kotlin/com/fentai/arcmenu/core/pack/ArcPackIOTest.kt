package com.fentai.arcmenu.core.pack

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArcPackIOTest {
    @TempDir lateinit var directory: Path
    private fun manifest(files: Map<String, ByteArray>) = ArcPackManifest("demo", "1.0.0", "Demo \"pack\"", "Unicode: \u4e2d\u6587", ArcPackAuthor("00000000-0000-0000-0000-000000000001", "Author"),
        listOf(ArcPackProject("menu", "shop", "menus/shop.yml")), files.map { (path, bytes) -> ArcPackFile(path, ArcPackIO.sha256(bytes), bytes.size.toLong()) }, listOf("font:example:custom"))
    private fun zip(path: Path, files: Map<String, ByteArray>) {
        ZipOutputStream(Files.newOutputStream(path)).use { out -> files.forEach { (name, data) -> out.putNextEntry(ZipEntry(name)); out.write(data); out.closeEntry() } }
    }
    @Test fun `archive round trip checks exact hashes UTF8 metadata and never overwrites`() {
        val files = linkedMapOf("menus/shop.yml" to "schema-version: 1\nid: shop\n".toByteArray(), "images/icon.png" to byteArrayOf(1, 2, 3))
        val source = manifest(files); val path = directory.resolve("demo.arcpack")
        ArcPackIO.write(path, source, files)
        val read = ArcPackIO.read(path)
        assertEquals(source, read.manifest); files.forEach { (k, v) -> assertArrayEquals(v, read.files[k]) }
        val original = Files.readAllBytes(path)
        assertThrows<IllegalArgumentException> { ArcPackIO.write(path, source.copy(title = "Replacement"), files) }
        assertArrayEquals(original, Files.readAllBytes(path))
    }
    @Test fun `file table rejects tampered missing and undeclared bytes`() {
        val files = mapOf("menus/shop.yml" to byteArrayOf(1, 2, 3)); val manifest = ArcPackIO.serializeManifest(manifest(files)).toByteArray()
        for (payload in listOf(mapOf("menus/shop.yml" to byteArrayOf(1, 2, 4)), emptyMap(), files + ("images/extra.png" to byteArrayOf(1)))) {
            val path = directory.resolve("test-${payload.size}-${payload.values.firstOrNull()?.lastOrNull()}.arcpack")
            zip(path, mapOf("manifest.json" to manifest) + payload)
            assertThrows<IllegalArgumentException> { ArcPackIO.read(path) }
        }
    }
    @Test fun `original GIF image assets round trip without admitting GIF publication previews`() {
        val files = linkedMapOf("menus/shop.yml" to "id: shop\n".toByteArray(), "images/motion/logo.gif" to "GIF89a".toByteArray())
        val path = directory.resolve("gif.arcpack")
        ArcPackIO.write(path, manifest(files), files)
        assertArrayEquals(files.getValue("images/motion/logo.gif"), ArcPackIO.read(path).files.getValue("images/motion/logo.gif"))
        for (unsupported in listOf("previews/menu-shop.gif", "images/motion/logo.GIF", "effects/logo.gif")) {
            val invalid = files + (unsupported to byteArrayOf(1))
            assertThrows<IllegalArgumentException> { ArcPackIO.write(directory.resolve("unsupported-${unsupported.substringAfterLast('/')}.arcpack"), manifest(invalid), invalid) }
        }
    }
    @Test fun `ZIP traversal absolute drive backslash unsupported and case aliases are rejected`() {
        val safe = mapOf("menus/shop.yml" to byteArrayOf(1)); val manifest = ArcPackIO.serializeManifest(manifest(safe)).toByteArray()
        for ((index, name) in listOf("../outside.yml", "/outside.yml", "C:/outside.yml", "menus\\shop.yml", "images/../outside.png", "plugins/payload.jar", "images/CON.png", "images/icon.png/evil").withIndex()) {
            val path = directory.resolve("unsafe$index.zip"); zip(path, mapOf("manifest.json" to manifest) + safe + (name to byteArrayOf(2)))
            assertThrows<IllegalArgumentException>(name) { ArcPackIO.read(path) }
            assertFalse(Files.exists(directory.resolve("outside.yml")))
        }
        val alias = directory.resolve("alias.zip"); zip(alias, mapOf("manifest.json" to manifest) + safe + ("menus/SHOP.yml" to byteArrayOf(1)))
        assertThrows<IllegalArgumentException> { ArcPackIO.read(alias) }
    }
    @Test fun `uncompressed size limits and entry counts are enforced while reading`() {
        val files = mapOf("menus/shop.yml" to ByteArray(8192)); val path = directory.resolve("bounded.zip")
        ArcPackIO.write(path, manifest(files), files)
        assertThrows<IllegalArgumentException> { ArcPackIO.read(path, ArcPackLimits(fileBytes = 1000)) }
        assertThrows<IllegalArgumentException> { ArcPackIO.read(path, ArcPackLimits(totalBytes = 4000)) }
        assertThrows<IllegalArgumentException> { ArcPackIO.read(path, ArcPackLimits(entries = 1)) }
    }
    @Test fun `manifest strictly requires JSON unique fields supported version and declared projects`() {
        val m = ArcPackIO.serializeManifest(manifest(mapOf("menus/shop.yml" to byteArrayOf(1))))
        for (invalid in listOf(m.replace("\"formatVersion\":1", "\"formatVersion\":2"), m.replace("\"formatVersion\":1", "\"formatVersion\":1.0"), m.replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1"), m.replace("\"format\":\"arcpack\"", "\"format\":\"unknown\""), m.replace("\"menus/shop.yml\"", "\"menus/missing.yml\"", true).replace("\"path\":\"menus/missing.yml\",\"sha256\"", "\"path\":\"menus/shop.yml\",\"sha256\""), "format: arcpack")) {
            assertThrows<IllegalArgumentException> { ArcPackIO.parseManifest(invalid) }
        }
        assertEquals(mapOf("a" to mapOf("b" to listOf(mapOf("c" to 1L)))), ArcPackJson.decode("{\"a\":{\"b\":[{\"c\":1}]}}"))
        assertThrows<IllegalArgumentException> { ArcPackJson.decode("{\"a\":1,}") }
    }
    @Test fun `only animation config is admitted and it may bind only declared menus with valid targets`() {
        val menu = "schema-version: 1\nid: shop\nfrontend:\n  title: {type: text, content: Shop}\n".toByteArray()
        val config = """
            schema-version: 1
            tracks:
              pulse:
                target: title
                property: opacity
                duration: 8
                keyframes: [{at: 0, value: 0}, {at: 1, value: 255}]
            menus:
              shop: {tracks: [pulse]}
        """.trimIndent()
        val files = mapOf("menus/shop.yml" to menu, "config/animations.yml" to config.toByteArray())
        val archive = directory.resolve("animated.arcpack"); ArcPackIO.write(archive, manifest(files), files)
        assertEquals(config, ArcPackIO.utf8(ArcPackIO.read(archive).files.getValue("config/animations.yml")))
        for ((index, invalid) in listOf(config.replace("shop:", "outside:"), config.replace("target: title", "target: missing"), config + "\nunknown: true\n").withIndex()) {
            val bytes = files + ("config/animations.yml" to invalid.toByteArray())
            val path = directory.resolve("bad-config$index.zip")
            zip(path, mapOf("manifest.json" to ArcPackIO.serializeManifest(manifest(bytes)).toByteArray()) + bytes)
            assertThrows<IllegalArgumentException> { ArcPackIO.read(path) }
        }
        val unsupported = files + ("config/server.yml" to byteArrayOf(1))
        assertThrows<IllegalArgumentException> { ArcPackIO.write(directory.resolve("unsupported.arcpack"), manifest(unsupported), unsupported) }
    }

    @Test fun `template projects use their own declared YAML namespace in template-only and mixed archives`() {
        val template = "schema-version: 1\nid: shop\nfrontend:\n  card: {type: group, children: {}}\n".toByteArray()
        val menu = "schema-version: 1\nid: shop\n".toByteArray()
        for ((index, mixed) in listOf(false, true).withIndex()) {
            val files = linkedMapOf("templates/cards/shop.yml" to template)
            if (mixed) files["menus/shop.yml"] = menu
            val projects = listOf(ArcPackProject("template", "shop", "templates/cards/shop.yml")) +
                if (mixed) listOf(ArcPackProject("menu", "shop", "menus/shop.yml")) else emptyList()
            val m = manifest(files).copy(projects = projects)
            val path = directory.resolve("templates$index.arcpack")
            ArcPackIO.write(path, m, files)
            assertEquals(m, ArcPackIO.read(path).manifest)
            assertArrayEquals(template, ArcPackIO.read(path).files.getValue("templates/cards/shop.yml"))
        }
        val files = mapOf("templates/cards/shop.yml" to template)
        val m = manifest(files).copy(projects = listOf(ArcPackProject("template", "shop", "templates/cards/shop.yml")))
        assertThrows<IllegalArgumentException> { ArcPackIO.parseManifest(ArcPackIO.serializeManifest(m.copy(
            projects = listOf(ArcPackProject("template", "shop", "menus/shop.yml"))))) }
        val undeclared = files + ("templates/other.yml" to template)
        assertThrows<IllegalArgumentException> { ArcPackIO.write(directory.resolve("undeclared.arcpack"),
            m.copy(files = undeclared.map { (p, b) -> ArcPackFile(p, ArcPackIO.sha256(b), b.size.toLong()) }), undeclared) }
        val animation = files + ("config/animations.yml" to "schema-version: 1\nmenus:\n  shop: {tracks: []}\n".toByteArray())
        assertThrows<IllegalArgumentException> { ArcPackIO.write(directory.resolve("template-animation.arcpack"),
            m.copy(files = animation.map { (p, b) -> ArcPackFile(p, ArcPackIO.sha256(b), b.size.toLong()) }), animation) }
    }

    @Test fun `hover animation projects preserve reactions and validate portable track bindings`() {
        val menu = """
            schema-version: 1
            id: shop
            frontend:
              title: {type: text, content: Shop}
            backend:
              highlight:
                width: 30
                height: 12
                on-hover: 'animation: pulse {Delay=2}'
                on-leave:
                  condition: 'perm shop.highlight'
                  actions: 'stop-animation: pulse'
                  deny: 'stop-animation: pulse'
        """.trimIndent()
        val animation = """
            schema-version: 1
            tracks:
              pulse:
                target: title
                property: opacity
                duration: 8
                trigger: api
                keyframes: [{at: 0, value: 128}, {at: 1, value: 255}]
            menus:
              shop: {tracks: [pulse]}
        """.trimIndent()
        val files = mapOf("menus/shop.yml" to menu.toByteArray(), "config/animations.yml" to animation.toByteArray())
        val archive = directory.resolve("hover.arcpack")
        ArcPackIO.write(archive, manifest(files), files)
        val read = ArcPackIO.read(archive)
        assertEquals(menu, ArcPackIO.utf8(read.files.getValue("menus/shop.yml")))
        assertEquals(animation, ArcPackIO.utf8(read.files.getValue("config/animations.yml")))

        val invalid = files + ("menus/shop.yml" to menu.replace("stop-animation: pulse", "stop-animation: missing").toByteArray())
        assertThrows<IllegalArgumentException> { ArcPackIO.write(directory.resolve("unbound-hover.arcpack"), manifest(invalid), invalid) }
    }
}
