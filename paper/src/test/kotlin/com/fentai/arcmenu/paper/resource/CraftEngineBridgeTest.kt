package com.fentai.arcmenu.paper.resource

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class CraftEngineBridgeTest {
    class Cache {
        val packs = linkedSetOf<Path>()
        fun externalZips(): MutableSet<Path> = packs
    }

    class Event {
        val cache = Cache()
        var brokenConvenienceMethodCalled = false
        fun cacheData(): Cache = cache
        fun registerExternalResourcePack(@Suppress("UNUSED_PARAMETER") path: Path) {
            brokenConvenienceMethodCalled = true
            throw IllegalArgumentException("26.6.3 convenience method rejected a valid zip")
        }
    }

    @Test
    fun `CE 26 cache set is preferred over its broken convenience method`() {
        val event = Event()
        val pack = Path.of("C:/server/plugins/ArcMenu/generated/arcmenu-resourcepack.zip")

        assertEquals("cacheData.externalZips", registerExternalPack(event, pack))
        assertTrue(pack in event.cache.packs)
        assertFalse(event.brokenConvenienceMethodCalled)
    }
}
