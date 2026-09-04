package com.fentai.arcmenu.core

import com.fentai.arcmenu.core.behavior.*
import com.fentai.arcmenu.core.config.MenuFormatException
import com.fentai.arcmenu.core.config.MenuParser
import com.fentai.arcmenu.core.model.TextNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MenuBehaviorTest {
    private val context = object : ConditionContext {
        override fun expand(value: String) = value.replace("%number%", "12").replace("%truth%", "true")
        override fun hasPermission(permission: String) = permission == "arcmenu.use"
    }

    @Test fun `common TrMenu style actions compile to typed actions`() {
        assertEquals(TellAction("hello"), ActionLanguage.parse("tell: hello"))
        assertEquals(PlayerCommandAction("spawn"), ActionLanguage.parse("player: /spawn"))
        assertEquals(ConsoleCommandAction("say hello"), ActionLanguage.parse("console: say hello"))
        assertEquals(OpenMenuAction("details"), ActionLanguage.parse("open: details"))
        assertEquals(OpenMenuAction("myaddon:shop/main"), ActionLanguage.parse("open: myaddon:shop/main"))
        assertEquals(OpenMenuAction("details", listOf("one", "two words")), ActionLanguage.parse("open: details one `two words`"))
        assertThrows<BehaviorSyntaxException> { ActionLanguage.parse("open: details:2") }
        assertEquals(RefreshAction(), ActionLanguage.parse("refresh: *"))
        assertEquals(PlayAnimationAction("card-bob"), ActionLanguage.parse("animate: card-bob"))
        assertEquals(StopAnimationAction("card-bob"), ActionLanguage.parse("stop-animation: card-bob"))
        assertSame(ReturnAction, ActionLanguage.parse("return"))
        assertThrows<BehaviorSyntaxException> { ActionLanguage.parse("[player] say wrong") }
        assertEquals(TitleAction("`Ready now` &7Go 10 20 10"), ActionLanguage.parse("title: `Ready now` &7Go 10 20 10"))
        val compound = ActionLanguage.parse("tell: one &&& sound: UI_BUTTON_CLICK {Delay=20}") as ActionLanguage.SequenceAction
        assertEquals(2, compound.actions.size)
        assertTrue(compound.actions.all { it is ConfiguredAction && it.options.delayTicks == 20L })
        val optioned = ActionLanguage.parse("actionbar: hi {Chance=0.5} {Condition=perm arcmenu.use} <players>") as ConfiguredAction
        assertEquals(0.5, optioned.options.chance)
        assertNotNull(optioned.options.condition)
        assertTrue(optioned.options.allOnlinePlayers)
    }

    @Test fun `open-app has one canonical spelling`() {
        assertEquals(
            OpenApplicationAction("myaddon:shop", listOf("daily", "two words")),
            ActionLanguage.parse("open-app: myaddon:shop daily `two words`"),
        )
        assertThrows<BehaviorSyntaxException> { ActionLanguage.parse("openapp: myaddon:shop") }
        assertThrows<BehaviorSyntaxException> { ActionLanguage.parse("open_app: myaddon:shop") }
        assertThrows<BehaviorSyntaxException> { ActionLanguage.parse("app: myaddon:shop") }
        assertThrows<BehaviorSyntaxException> { ActionLanguage.parse("open-app: shop") }
    }

    @Test fun `TrMenu mapped actions and chat catcher stages compile`() {
        val source = """
            schema-version: 1
            id: catcher
            frontend: {}
            backend:
              input:
                width: 20
                height: 10
                actions:
                  right:
                    - tell: '&aMapped action'
                    - catcher:
                        name:
                          type: CHAT
                          start: 'tell: enter a name'
                          cancel: 'tell: cancelled'
                          end:
                            - 'set-meta: accepted {meta:input}'
        """.trimIndent()
        val actions = MenuParser().parse(source, "catcher.yml").definition.backend.single().actions.matching(ClickInput.RIGHT)
        assertTrue(actions[0].actions.single() is TellAction)
        val catcher = actions[1].actions.single() as CatcherAction
        assertEquals("name", catcher.stages.single().id)
        assertTrue(catcher.stages.single().end.single().actions.single() is SetStateAction)
    }

    @Test fun `permission comparison and boolean composition evaluate deterministically`() {
        assertTrue(ConditionLanguage.evaluate(ConditionLanguage.parse("perm *arcmenu.use"), context))
        assertTrue(ConditionLanguage.evaluate(ConditionLanguage.parse("check %number% >= *10"), context))
        assertTrue(ConditionLanguage.evaluate(ConditionLanguage.parse("perm arcmenu.use && %truth%"), context))
        assertTrue(ConditionLanguage.evaluate(ConditionLanguage.parse("any [ perm missing ; check %number% is *12 ]"), context))
        assertFalse(ConditionLanguage.evaluate(ConditionLanguage.parse("not perm arcmenu.use"), context))
        assertThrows<BehaviorSyntaxException> { ConditionLanguage.parse("javascript arbitrary code") }
    }

    @Test fun `fixture preserves all plus right order and conditional priority`() {
        val source = requireNotNull(javaClass.getResourceAsStream("/fixtures/trmenu-m2.yml")).bufferedReader().use { it.readText() }
        val menu = MenuParser().parse(source, "fixtures/trmenu-m2.yml").definition
        assertEquals(20, (menu.frontend.single() as TextNode).updateTicks)
        val region = menu.backend.single()
        assertEquals(20, region.tooltipUpdateTicks)
        val reactions = region.actions.matching(ClickInput.RIGHT)
        assertEquals(3, reactions.size)
        assertTrue(reactions[0].actions.single() is SoundAction)
        assertTrue(reactions[1].actions.single() is RefreshAction)
        assertTrue(reactions[2].actions.single() is TellAction)
        assertNotNull(reactions[2].condition)
        assertTrue(region.actions.matching(ClickInput.LEFT).single().actions.single() is SoundAction)
    }

    @Test fun `unsupported action and invalid update fail at exact backend path`() {
        val invalidAction = "schema-version: 1\nid: bad\nfrontend: {}\nbackend:\n  hit:\n    width: 1\n    height: 1\n    actions: 'page: 2'\n"
        val actionError = assertThrows<MenuFormatException> { MenuParser().parse(invalidAction, "bad.yml") }
        assertTrue(actionError.message!!.contains("bad.yml.backend.hit.actions"))
        val invalidUpdate = invalidAction.replace("    actions: 'page: 2'", "    update: 0")
        val updateError = assertThrows<MenuFormatException> { MenuParser().parse(invalidUpdate, "bad.yml") }
        assertTrue(updateError.message!!.contains("bad.yml.backend.hit.update"))
    }
}
