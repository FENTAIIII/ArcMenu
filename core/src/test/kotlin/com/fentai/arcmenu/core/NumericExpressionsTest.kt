package com.fentai.arcmenu.core

import com.fentai.arcmenu.core.behavior.ActionLanguage
import com.fentai.arcmenu.core.behavior.ConditionContext
import com.fentai.arcmenu.core.behavior.ConditionLanguage
import com.fentai.arcmenu.core.behavior.ConfiguredAction
import com.fentai.arcmenu.core.behavior.NumericExpressionException
import com.fentai.arcmenu.core.behavior.NumericExpressions
import com.fentai.arcmenu.core.behavior.TellAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NumericExpressionsTest {
    private fun context(quantity: String = "3", money: String = "100") = object : ConditionContext {
        override fun expand(value: String): String = value.replace("{meta:input-x}", quantity).replace("%money%", money)
        override fun hasPermission(permission: String) = false
    }

    private fun check(source: String, quantity: String = "3", money: String = "100"): Boolean =
        ConditionLanguage.evaluate(ConditionLanguage.parse(source), context(quantity, money))

    @Test fun `quantity times price is evaluated after placeholder expansion`() {
        assertTrue(check("%money% >= {meta:input-x}*24"))
        assertFalse(check("%money% >= {meta:input-x}*24", quantity = "5"))
        assertTrue(check("%money% >= max(1, {meta:input-x}) * (20 + 4) && 1 < 2"))
        assertThrows<NumericExpressionException> { check("%money% >= {meta:input-x}*24", quantity = "not-a-number") }
        assertThrows<NumericExpressionException> { check("%money% >= {meta:input-x}", quantity = "null") }
        assertThrows<NumericExpressionException> { check("not %money% >= {meta:input-x}*24", quantity = "invalid") }
    }

    @Test fun `arithmetic follows normal precedence with right associative powers`() {
        assertEquals(14.0, NumericExpressions.evaluate("2 + 3 * 4"))
        assertEquals(20.0, NumericExpressions.evaluate("(2 + 3) * 4"))
        assertEquals(512.0, NumericExpressions.evaluate("2^3^2"))
        assertEquals(-4.0, NumericExpressions.evaluate("-2^2"))
        assertEquals(4.0, NumericExpressions.evaluate("(-2)^2"))
        assertEquals(0.25, NumericExpressions.evaluate("2^-2"))
        assertEquals(7.0, NumericExpressions.evaluate("1 - -2 * +3"))
        assertEquals(1.0, NumericExpressions.evaluate("7 % 3"))
        assertEquals(-1.0, NumericExpressions.evaluate("-7 % 3"))
        assertEquals(120.0, NumericExpressions.evaluate("1.2e2"))
        assertEquals(0.505, NumericExpressions.evaluate(".5 + 5.e-3"))
    }

    @Test fun `functions support quantities and rounded prices`() {
        assertEquals(5.0, NumericExpressions.evaluate("max(1, min(10, abs(-5)))"))
        assertEquals(-2.0, NumericExpressions.evaluate("floor(-1.1)"))
        assertEquals(-1.0, NumericExpressions.evaluate("ceil(-1.1)"))
        assertEquals(3.0, NumericExpressions.evaluate("round(2.5)"))
        assertEquals(-3.0, NumericExpressions.evaluate("round(-2.5)"))
        assertEquals(2.68, NumericExpressions.evaluate("round(2.675, 2)"))
        assertEquals(-2.68, NumericExpressions.evaluate("round(-2.675, 2)"))
        assertEquals(2.0, NumericExpressions.evaluate("MAX(1, 2)"))
        assertThrows<NumericExpressionException> { NumericExpressions.evaluate("round(1.234, 1.5)") }
        assertThrows<NumericExpressionException> { NumericExpressions.evaluate("round(1, 16)") }
        assertThrows<NumericExpressionException> { NumericExpressions.evaluate("abs(1, 2)") }
        assertThrows<NumericExpressionException> { NumericExpressions.evaluate("min()") }
    }

    @Test fun `invalid and nonfinite results never become valid comparisons`() {
        for (source in listOf("1/0", "1%0", "1e309", "10^309", "(-1)^.5", "NaN", "Infinity", "1 +", "1 ** 2", "1e", "2(3)", "unknown(1)", "(1+2")) {
            assertThrows<NumericExpressionException>(source) { NumericExpressions.evaluate(source) }
        }
        assertThrows<NumericExpressionException> { check("10 >= 3*") }
        assertThrows<NumericExpressionException> { check("10 >= 1/0") }
        assertThrows<NumericExpressionException> { check("NaN == NaN") }
        assertThrows<NumericExpressionException> { NumericExpressions.evaluate("(".repeat(70) + "1" + ")".repeat(70)) }
    }

    @Test fun `string equality contains and plain string ordering remain compatible`() {
        assertTrue(check("hello == HELLO"))
        assertTrue(check("'3*4' == `3*4`"))
        assertTrue(check("'a+b' == 'A+B'"))
        assertTrue(check("'product-code' == product-code"))
        assertTrue(check("'Alpha Beta' contains 'beta'"))
        assertTrue(check("'3*4' contains '*'"))
        assertTrue(check("zebra > apple"))
        assertTrue(check("check '1e2' == *100"))
        assertTrue(check("1 + 2 == 3"))
        assertTrue(check("-2-3 == -5"))
        assertTrue(check("- -2 == 2"))
        assertFalse(check("red == 3"))
    }

    @Test fun `calculated placeholders support nested calculations and clean decimal output`() {
        val expanded = NumericExpressions.expandPlaceholders("Cost: {math: 3 * 24}; tax: {math: round(72 * 0.07, 2)}")
        assertEquals("Cost: 72; tax: 5.04", expanded)
        assertEquals("14", NumericExpressions.expandPlaceholders("{math: {math: 2 + 3} + 9}"))
        assertEquals("0.3", NumericExpressions.expandPlaceholders("{math: .1 + .2}"))
        assertEquals("0", NumericExpressions.format(-0.0))
        assertEquals("100000000000000000000", NumericExpressions.expandPlaceholders("{math: 1e20}"))
        assertEquals("0.00000000000000000001", NumericExpressions.expandPlaceholders("{math: 1e-20}"))
        assertEquals("literal text", NumericExpressions.expandPlaceholders("literal text"))
        assertThrows<NumericExpressionException> { NumericExpressions.expandPlaceholders("{math: 1 + }") }
        assertThrows<NumericExpressionException> { NumericExpressions.expandPlaceholders("{math: 1 + 2") }
        assertThrows<NumericExpressionException> { NumericExpressions.expandPlaceholders("{math: ".repeat(70) + "1" + "}".repeat(70)) }
    }

    @Test fun `inline condition options keep nested dynamic math placeholders intact`() {
        val action = ActionLanguage.parse("tell: purchased {Condition=check %money% >= {meta:input-x}*24}") as ConfiguredAction
        assertEquals(TellAction("purchased"), action.action)
        assertTrue(ConditionLanguage.evaluate(requireNotNull(action.options.condition), context()))
        val calculated = ActionLanguage.parse("tell: purchased {Condition=check %money% >= {math: {meta:input-x}*24}}") as ConfiguredAction
        val calculatedContext = object : ConditionContext {
            override fun expand(value: String) = NumericExpressions.expandPlaceholders(context().expand(value))
            override fun hasPermission(permission: String) = false
        }
        assertTrue(ConditionLanguage.evaluate(requireNotNull(calculated.options.condition), calculatedContext))
        val quoted = ActionLanguage.parse("tell: matched {Condition=check 'brace}' == 'brace}'}") as ConfiguredAction
        assertTrue(ConditionLanguage.evaluate(requireNotNull(quoted.options.condition), context()))
    }
}
