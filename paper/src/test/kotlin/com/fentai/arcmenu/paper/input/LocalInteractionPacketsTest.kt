package com.fentai.arcmenu.paper.input

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalInteractionPacketsTest {
    enum class Hand { MAIN_HAND, OFF_HAND }
    enum class Kind { ATTACK, INTERACT, INTERACT_AT }
    class Action(private val kind: Kind, private val hand: Hand) { fun getType() = kind }
    class Legacy(private val entityId: Int, private val action: Action)
    class Interact(private val entityId: Int, private val hand: Hand, private val location: Any?) {
        fun hand() = hand
        fun location() = location
    }
    class Attack(private val entityId: Int) { fun entityId() = entityId }

    @Test fun `1_21_11 action hierarchy preserves left right and duplicate suppression`() {
        val reader = LocalInteractionPackets(Legacy::class.java, null)
        for (kind in Kind.entries) for (hand in Hand.entries) {
            val click = reader.read(Legacy(42, Action(kind, hand)))!!
            assertEquals(42, click.entityId)
            assertEquals(kind == Kind.ATTACK, click.primary)
            assertEquals(kind == Kind.ATTACK || (kind == Kind.INTERACT && hand == Hand.MAIN_HAND), click.dispatch)
        }
        assertNull(reader.read(Any()))
    }

    @Test fun `26x split attack and single right click with hit position preserve dispatch`() {
        val reader = LocalInteractionPackets(Interact::class.java, Attack::class.java)
        assertEquals(LocalInteractionPackets.Click(77, true, true), reader.read(Attack(77)))
        assertEquals(LocalInteractionPackets.Click(77, false, true), reader.read(Interact(77, Hand.MAIN_HAND, null)))
        assertTrue(reader.read(Interact(77, Hand.MAIN_HAND, Any()))!!.dispatch)
        assertFalse(reader.read(Interact(77, Hand.OFF_HAND, null))!!.dispatch)
        assertNull(reader.read(Any()))
    }

}
