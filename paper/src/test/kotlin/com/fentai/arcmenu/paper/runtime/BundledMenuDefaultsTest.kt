package com.fentai.arcmenu.paper.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BundledMenuDefaultsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `fresh installation seeds samples once and deleted menus stay deleted`() {
        val menus = directory.resolve("menus")
        val copied = mutableListOf<String>()
        val copy: (String) -> Unit = { resource ->
            copied += resource
            val destination = directory.resolve(resource)
            Files.createDirectories(destination.parent)
            Files.writeString(destination, "sample")
        }
        BundledMenuDefaults.install(menus, copy)
        assertEquals(BundledMenuDefaults.resources.size, copied.size)
        Files.delete(menus.resolve("example.yml"))
        Files.delete(directory.resolve("animations.yml"))
        Files.delete(directory.resolve("images/example.gif"))
        copied.clear()
        BundledMenuDefaults.install(menus, copy)
        assertTrue(copied.isEmpty())
        assertTrue(Files.notExists(menus.resolve("example.yml")))
        assertTrue(Files.notExists(directory.resolve("animations.yml")))
        assertTrue(Files.notExists(directory.resolve("images/example.gif")))
    }

    @Test
    fun `upgrades respect an existing empty menu directory`() {
        val menus = Files.createDirectory(directory.resolve("menus"))
        val copied = mutableListOf<String>()
        BundledMenuDefaults.install(menus, copied::add)
        assertTrue(copied.isEmpty())
    }

    @Test
    fun `removing the entire menu directory does not reinstall deleted samples`() {
        val menus = Files.createDirectory(directory.resolve("menus"))
        val copied = mutableListOf<String>()
        BundledMenuDefaults.install(menus, copied::add)
        Files.delete(menus)
        BundledMenuDefaults.install(menus, copied::add)
        assertTrue(copied.isEmpty())
        assertTrue(Files.isDirectory(menus))
    }
}
