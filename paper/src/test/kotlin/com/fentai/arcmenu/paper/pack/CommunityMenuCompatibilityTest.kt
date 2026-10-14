package com.fentai.arcmenu.paper.pack

import com.fentai.arcmenu.core.behavior.ClickInput
import com.fentai.arcmenu.core.config.MenuParser
import com.fentai.arcmenu.core.model.MenuDocument
import com.fentai.arcmenu.core.pack.*
import com.fentai.arcmenu.paper.editor.EditorDraft
import com.fentai.arcmenu.protocol.EditorProtocol
import com.google.gson.Gson
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CommunityMenuCompatibilityTest {
    @TempDir lateinit var root: Path
    private val parser = MenuParser()
    private val author = ArcPackAuthor("00000000-0000-0000-0000-000000000001", "Author")
    private fun source() = requireNotNull(javaClass.getResourceAsStream("/compatibility/full-menu.yml"))
        .bufferedReader().use { it.readText() }
    private fun documents(directory: Path): Collection<MenuDocument> {
        val menus = directory.resolve("menus")
        if (!Files.exists(menus)) return emptyList()
        return Files.walk(menus).use { paths -> paths.filter { Files.isRegularFile(it) }
            .map { parser.parse(Files.readString(it), menus.relativize(it).toString()) }.toList() }
    }

    @Test fun `full schema survives editor save and package export install`() {
        val original = parser.parse(source()).definition
        val file = root.resolve("menus/compatible.yml")
        Files.createDirectories(file.parent)
        Files.writeString(file, source())
        val draft = EditorDraft(UUID.randomUUID(), file, original.id, source(), original,
            {}, { parser.parse(it).definition }, root.resolve("backup.yml"))
        draft.setProperty(0, EditorProtocol.TAB_FRONTEND, "motion", "speed", "1.5")
        draft.create(draft.revision, EditorProtocol.TAB_FRONTEND, EditorProtocol.KIND_RECTANGLE, "")
        draft.save(draft.revision)
        val saved = parser.parse(Files.readString(file)).definition
        assertEquals(original.backend, saved.backend)
        assertEquals(original.events, saved.events)
        assertEquals(original.canvas, saved.canvas)
        assertEquals(original.permissionDeniedMenu, saved.permissionDeniedMenu)
        assertEquals(original.openCommands, saved.openCommands)
        assertEquals(original.mainMenu, saved.mainMenu)
        assertEquals(6, ClickInput.entries.count { saved.backend.single().actions.matching(it).isNotEmpty() })

        val gif = requireNotNull(javaClass.getResourceAsStream("/images/example.gif")).use { it.readAllBytes() }
        Files.createDirectories(root.resolve("images"))
        Files.write(root.resolve("images/motion.gif"), gif)
        val png = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", it)
        }.toByteArray()
        Files.write(root.resolve("images/icon.png"), png)
        val exporter = ArcPackService(root, { documents(root) }, {})
        val pack = exporter.export(ArcPackExportRequest("compatible", "1.0.0", "Compatible", "Complete compatible menu", author,
            listOf(ArcPackSelection("menu", "compatible"))))
        val target = root.resolve("target")
        Files.createDirectories(target)
        ArcPackService(target, { documents(target) }, {}).install(pack.archive)
        val installed = documents(target).single().definition
        assertEquals(saved.backend, installed.backend)
        assertEquals(saved.events, installed.events)
        assertEquals(saved.permissionDeniedMenu, installed.permissionDeniedMenu)
        assertEquals(saved.openCommands, installed.openCommands)
        assertArrayEquals(gif, Files.readAllBytes(target.resolve("images/arcpack-compatible/motion.gif")))
    }

    @Test fun `Effect assets or declarations reject the whole archive before installation`() {
        for (effect in listOf("effects/demo/scene.yml", "effects/assets/demo.ogg", "effects/assets/demo.nbs")) {
            val menu = source().toByteArray()
            val files = linkedMapOf("menus/compatible.yml" to menu, effect to byteArrayOf(1))
            val manifest = ArcPackManifest("rejected", "1.0.0", "Rejected", "", author,
                listOf(ArcPackProject("menu", "compatible", "menus/compatible.yml")),
                files.map { (path, bytes) -> ArcPackFile(path, ArcPackIO.sha256(bytes), bytes.size.toLong()) }, emptyList())
            val archive = root.resolve("rejected.arcpack")
            ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
                (files + ("manifest.json" to ArcPackIO.serializeManifest(manifest).toByteArray())).forEach { (path, bytes) ->
                    zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
                }
            }
            val target = root.resolve("rejected-target")
            Files.createDirectories(target)
            assertThrows(IllegalArgumentException::class.java) { ArcPackService(target, { emptyList() }, {}).install(archive) }
            Files.list(target).use { assertEquals(0, it.count()) }
        }
    }

    @Test fun `both editions parse complete menus and editor output identically`() {
        val premiumJar = System.getProperty("arcmenu.premium.jar")
        assumeTrue(premiumJar != null, "Set arcmenu.premium.jar to run the cross-edition comparison")
        val url = Path.of(premiumJar).toUri().toURL()
        val loader = object : URLClassLoader(arrayOf(url), javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (!name.startsWith("com.fentai.arcmenu.core.")) return super.loadClass(name, resolve)
                return (findLoadedClass(name) ?: findClass(name)).also { if (resolve) resolveClass(it) }
            }
        }
        loader.use {
            val type = loader.loadClass("com.fentai.arcmenu.core.config.MenuParser")
            val premiumParser = type.getConstructor().newInstance()
            val parse = type.getMethod("parse", String::class.java, String::class.java)
            val definition = loader.loadClass("com.fentai.arcmenu.core.model.MenuDocument").getMethod("getDefinition")
            val normalized = loader.loadClass("com.fentai.arcmenu.core.model.MenuDocument").getMethod("getSource")
            val file = root.resolve("compatible.yml")
            Files.writeString(file, source())
            val draft = EditorDraft(UUID.randomUUID(), file, "compatible", source(), parser.parse(source()).definition,
                {}, { parser.parse(it).definition }, root.resolve("backup.yml"))
            draft.create(0, EditorProtocol.TAB_BACKEND, EditorProtocol.KIND_REGION, "")
            draft.save(draft.revision)
            val gson = Gson()
            val arcitx = requireNotNull(javaClass.getResourceAsStream("/menus/arcitx-test.yml"))
                .bufferedReader().use { it.readText() }
            val calculated = """
                schema-version: 1
                id: calculated
                canvas: {pixels-per-block: 42.7, depth-mode: layered}
                frontend:
                  total: {type: text, content: '{math: round(3 * 24 * 0.95, 2)}'}
                backend:
                  buy:
                    width: 20
                    height: 20
                    condition: '1000 >= {meta:input-x} * 24'
                    actions:
                      right:
                        - 'set-meta: total {math: {meta:input-x} * 24}'
                        - 'take-money: round({meta:input-x} * 24, 2)'
                        - 'tell: Budget accepted {condition=1000 >= {meta:input-x} * 24}'
            """.trimIndent()
            for (yaml in listOf(source(), Files.readString(file), arcitx, calculated)) {
                val community = parser.parse(yaml)
                val paid = parse.invoke(premiumParser, yaml, "menu.yml")
                assertEquals(gson.toJson(definition.invoke(paid)), gson.toJson(community.definition))
                // Move the normalized author document across the boundary in both directions.
                assertEquals(gson.toJson(community.definition), gson.toJson(definition.invoke(parse.invoke(premiumParser, community.source, "menu.yml"))))
                assertEquals(gson.toJson(community.definition), gson.toJson(parser.parse(normalized.invoke(paid) as String).definition))
            }
        }
    }
}
