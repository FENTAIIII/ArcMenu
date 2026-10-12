package com.fentai.arcmenu.paper.runtime

import com.fentai.arcmenu.core.behavior.ActionReaction
import com.fentai.arcmenu.core.behavior.BooleanCondition
import com.fentai.arcmenu.core.behavior.TellAction
import com.fentai.arcmenu.core.geometry.HitTester
import com.fentai.arcmenu.core.model.InteractionRegion
import com.fentai.arcmenu.core.model.MenuPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MenuHoverStateTest {
    @Test
    fun `stationary pointer does not restart the enter action`() {
        val state = MenuHoverState()
        val target = region("button")
        val events = mutableListOf<String>()
        var evaluations = 0
        repeat(100) {
            assertTrue(state.update(target, { true }, { evaluations++; true }, { record(it, events) }))
        }
        assertEquals(listOf("button.enter"), events)
        assertEquals(1, evaluations)
    }

    @Test
    fun `moving between targets runs leave before enter`() {
        val state = MenuHoverState()
        val events = mutableListOf<String>()
        state.update(region("first"), { true }, { true }, { record(it, events) })
        state.update(region("second"), { true }, { true }, { record(it, events) })
        assertEquals(listOf("first.enter", "first.leave", "second.enter"), events)
    }

    @Test
    fun `leaving the menu and reentering the same target triggers a fresh enter`() {
        val state = MenuHoverState()
        val events = mutableListOf<String>()
        val target = region("button")
        state.update(null, { true }, { true }, { record(it, events) })
        state.update(target, { true }, { true }, { record(it, events) })
        state.update(null, { true }, { true }, { record(it, events) })
        state.update(null, { true }, { true }, { record(it, events) })
        state.update(target, { true }, { true }, { record(it, events) })
        assertEquals(listOf("button.enter", "button.leave", "button.enter"), events)
    }

    @Test
    fun `the actual highest priority hit masks lower hover regions`() {
        val lower = region("lower")
        val upper = region("upper", priority = 1, enter = false, leave = false)
        val tester = HitTester(listOf(lower, upper))
        val state = MenuHoverState()
        val events = mutableListOf<String>()
        state.update(tester.hit(MenuPoint(0.0, 0.0)), { true }, { true }, { record(it, events) })
        assertEquals(emptyList<String>(), events)
        assertFalse(state.changed(upper))
        assertTrue(state.changed(lower))
    }

    @Test
    fun `a denied top region does not fall through or refire when permission changes in place`() {
        val lower = region("lower")
        val upper = region("upper", priority = 1).copy(condition = BooleanCondition(false))
        val tester = HitTester(listOf(lower, upper))
        val state = MenuHoverState()
        val events = mutableListOf<String>()
        var accepted = false
        val hit = tester.hit(MenuPoint(0.0, 0.0))
        state.update(hit, { true }, { accepted }, { record(it, events) })
        accepted = true
        state.update(hit, { true }, { accepted }, { record(it, events) })
        assertEquals(emptyList<String>(), events)
        state.update(null, { true }, { accepted }, { record(it, events) })
        state.update(hit, { true }, { accepted }, { record(it, events) })
        assertEquals(listOf("upper.leave", "upper.enter"), events)
    }

    @Test
    fun `region conditions also gate leave actions`() {
        val state = MenuHoverState()
        val events = mutableListOf<String>()
        state.update(region("button"), { true }, { true }, { record(it, events) })
        state.update(null, { true }, { false }, { record(it, events) })
        assertEquals(listOf("button.enter"), events)
    }

    @Test
    fun `a leave-only region still remembers the target`() {
        val state = MenuHoverState()
        val events = mutableListOf<String>()
        state.update(region("button", enter = false), { true }, { true }, { record(it, events) })
        state.update(null, { true }, { true }, { record(it, events) })
        assertEquals(listOf("button.leave"), events)
    }

    @Test
    fun `a leave action replacing or closing the session prevents the stale enter`() {
        val state = MenuHoverState()
        val events = mutableListOf<String>()
        var active = true
        state.update(region("first"), { active }, { true }, { record(it, events) })
        assertFalse(state.update(region("second"), { active }, { true }) {
            record(it, events)
            active = false
        })
        assertEquals(listOf("first.enter", "first.leave"), events)
    }

    @Test
    fun `reentrant target changes do not execute an outdated enter`() {
        val state = MenuHoverState()
        val events = mutableListOf<String>()
        val second = region("second", leave = false)
        val third = region("third")
        state.update(region("first"), { true }, { true }, { record(it, events) })
        assertFalse(state.update(second, { true }, { true }) {
            record(it, events)
            state.update(third, { true }, { true }, { reactions -> record(reactions, events) })
        })
        assertEquals(listOf("first.enter", "first.leave", "third.enter"), events)
        assertFalse(state.changed(third))
    }

    @Test
    fun `inactive sessions execute no author actions`() {
        val state = MenuHoverState()
        val events = mutableListOf<String>()
        assertFalse(state.update(region("button"), { false }, { true }, { record(it, events) }))
        assertEquals(emptyList<String>(), events)
        assertTrue(state.changed(region("button")))
    }

    @Test
    fun `closing inside enter stops further tick work`() {
        val state = MenuHoverState()
        val events = mutableListOf<String>()
        var active = true
        assertFalse(state.update(region("button"), { active }, { true }) {
            record(it, events)
            active = false
        })
        assertEquals(listOf("button.enter"), events)
    }

    private fun region(id: String, priority: Int = 0, enter: Boolean = true, leave: Boolean = true) = InteractionRegion(
        id = id,
        x = 0.0,
        y = 0.0,
        width = 20.0,
        height = 10.0,
        priority = priority,
        onHover = if (enter) listOf(ActionReaction(0, 0, actions = listOf(TellAction("$id.enter")))) else emptyList(),
        onLeave = if (leave) listOf(ActionReaction(0, 1, actions = listOf(TellAction("$id.leave")))) else emptyList(),
    )

    private fun record(reactions: List<ActionReaction>, events: MutableList<String>) {
        reactions.flatMap { it.actions }.forEach { events += (it as TellAction).message }
    }
}
