package com.fentai.arcmenu.paper.runtime

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class MenuCommandRegistryTest {
    @Test
    fun `unregister removes every owned label without touching foreign commands`() {
        val owned = TestCommand("arc")
        val foreign = TestCommand("other")
        val commands: MutableMap<String, Command> = linkedMapOf(
            "arc" to owned,
            "arcmenu:arc" to owned,
            "other" to foreign,
        )

        unregisterCommandMappings(commands, setOf(owned))

        assertEquals(setOf("other"), commands.keys)
        assertSame(foreign, commands["other"])
    }

    private class TestCommand(name: String) : Command(name) {
        override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>) = true
    }
}
