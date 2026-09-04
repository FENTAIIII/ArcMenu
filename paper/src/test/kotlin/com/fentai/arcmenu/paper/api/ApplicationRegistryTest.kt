package com.fentai.arcmenu.paper.api

import com.fentai.arcmenu.api.ArcMenuApplication
import com.fentai.arcmenu.api.ArcMenuApplicationOptions
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Proxy

class ApplicationRegistryTest {
    @Test
    fun `registration is namespaced exclusive and owner scoped`() {
        val registry = ApplicationRegistry()
        val first = plugin("First")
        val second = plugin("Second")
        val application = ArcMenuApplication { error("not opened in registry test") }

        val registration = registry.register(first, "first:shop", ArcMenuApplicationOptions.DEFAULT, application)
        assertEquals(setOf("first:shop"), registry.ids())
        assertTrue(registry.contains(registration.token))
        assertThrows<IllegalArgumentException> {
            registry.register(second, "first:shop", ArcMenuApplicationOptions.DEFAULT, application)
        }
        assertThrows<IllegalArgumentException> {
            registry.register(first, "shop", ArcMenuApplicationOptions.DEFAULT, application)
        }

        assertEquals(listOf(registration), registry.unregister(first))
        assertFalse(registry.contains(registration.token))
    }

    @Test
    fun `invalid application options are rejected before publication`() {
        val registry = ApplicationRegistry()
        val options = ArcMenuApplicationOptions.builder().tickInterval(0).build()
        assertThrows<IllegalArgumentException> {
            registry.register(plugin("Owner"), "owner:bad", options, ArcMenuApplication { error("unused") })
        }
    }

    private fun plugin(name: String, enabled: Boolean = true): Plugin {
        return Proxy.newProxyInstance(Plugin::class.java.classLoader, arrayOf(Plugin::class.java)) { proxy, method, args ->
            when (method.name) {
                "getName" -> name
                "isEnabled" -> enabled
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> name
                else -> defaultValue(method.returnType)
            }
        } as Plugin
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Byte::class.javaPrimitiveType -> 0.toByte()
        Short::class.javaPrimitiveType -> 0.toShort()
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        Char::class.javaPrimitiveType -> '\u0000'
        else -> null
    }
}
