package com.fentai.arcmenu.core.behavior

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

class NumericExpressionException(
    val expression: String,
    val position: Int,
    val reason: String,
) : IllegalArgumentException("$reason at character ${position + 1}")

/** Small numeric language shared by conditions, actions and calculated placeholders. */
object NumericExpressions {
    private val functionStart = Regex("(?i)^(abs|min|max|floor|ceil|round)\\s*\\(")
    private val signedNumberStart = Regex("^[+-]\\s*(?:[+-]\\s*)*[0-9.(]")
    private val calculatedPlaceholder = Regex("\\{math:\\s*([^{}]*)}", RegexOption.IGNORE_CASE)
    private val calculatedStart = Regex("\\{math:", RegexOption.IGNORE_CASE)
    private val displayPrecision = MathContext(15, RoundingMode.HALF_UP)

    fun evaluate(source: String): Double = Parser(source).parse()

    fun format(value: Double): String {
        require(value.isFinite()) { "Numeric results must be finite" }
        return if (value == 0.0) "0" else BigDecimal.valueOf(value).round(displayPrecision).stripTrailingZeros().toPlainString()
    }

    /** Call after ordinary placeholders have been expanded; inner calculations resolve first. */
    fun expandPlaceholders(source: String): String {
        var value = source
        repeat(64) {
            if (!calculatedPlaceholder.containsMatchIn(value)) {
                val unfinished = calculatedStart.find(value) ?: return value
                throw NumericExpressionException(value, unfinished.range.first, "Malformed calculated placeholder")
            }
            value = calculatedPlaceholder.replace(value) { format(evaluate(it.groupValues[1])) }
        }
        val unfinished = calculatedStart.find(value) ?: return value
        throw NumericExpressionException(value, unfinished.range.first, "Calculated placeholder nesting exceeds 64 levels")
    }

    /** Plain words retain the condition language's string comparison semantics. */
    fun isNumeric(source: String): Boolean {
        val value = source.trim()
        if (value.isEmpty()) return false
        if (value.toDoubleOrNull() != null || functionStart.containsMatchIn(value) || signedNumberStart.containsMatchIn(value)) return true
        if (value.first().isDigit() || value.first() == '.') return true
        return value.any { it in "+*/%^()" }
    }

    private class Parser(private val source: String) {
        private var offset = 0
        private var depth = 0

        fun parse(): Double {
            whitespace()
            if (offset == source.length) fail("Numeric expression must not be empty")
            val value = sum()
            whitespace()
            if (offset != source.length) fail("Unexpected '${source[offset]}'")
            return value
        }

        private fun sum(): Double {
            var value = product()
            while (true) {
                value = when {
                    take('+') -> finite(value + product())
                    take('-') -> finite(value - product())
                    else -> return value
                }
            }
        }

        private fun product(): Double {
            var value = unary()
            while (true) {
                value = when {
                    take('*') -> finite(value * unary())
                    take('/') -> {
                        val divisor = unary()
                        if (divisor == 0.0) fail("Division by zero")
                        finite(value / divisor)
                    }
                    take('%') -> {
                        val divisor = unary()
                        if (divisor == 0.0) fail("Remainder by zero")
                        finite(value % divisor)
                    }
                    else -> return value
                }
            }
        }

        private fun unary(): Double = nested {
            when {
                take('+') -> unary()
                take('-') -> finite(-unary())
                else -> power()
            }
        }

        private fun power(): Double {
            val value = primary()
            return if (take('^')) finite(value.pow(unary())) else value
        }

        private fun primary(): Double {
            whitespace()
            if (take('(')) {
                val value = sum()
                expect(')')
                return value
            }
            if (offset < source.length && source[offset].isLetter()) return function()
            return number()
        }

        private fun function(): Double {
            val start = offset
            while (offset < source.length && source[offset].isLetter()) offset++
            val name = source.substring(start, offset).lowercase(Locale.ROOT)
            if (name !in listOf("abs", "min", "max", "floor", "ceil", "round")) fail("Unknown numeric function '$name'", start)
            expect('(')
            val arguments = mutableListOf<Double>()
            if (!take(')')) {
                do { arguments += sum() } while (take(','))
                expect(')')
            }
            val validCount = when (name) {
                "min", "max" -> arguments.isNotEmpty()
                "round" -> arguments.size in 1..2
                else -> arguments.size == 1
            }
            if (!validCount) {
                val expected = when (name) {
                    "min", "max" -> "at least one argument"
                    "round" -> "one or two arguments"
                    else -> "one argument"
                }
                fail("$name requires $expected", start)
            }
            return finite(when (name) {
                "abs" -> abs(arguments.single())
                "min" -> arguments.min()
                "max" -> arguments.max()
                "floor" -> floor(arguments.single())
                "ceil" -> ceil(arguments.single())
                else -> {
                    val places = arguments.getOrElse(1) { 0.0 }
                    if (places != floor(places) || places !in 0.0..15.0) fail("round decimal places must be an integer from 0 to 15", start)
                    BigDecimal.valueOf(arguments[0]).setScale(places.toInt(), RoundingMode.HALF_UP).toDouble()
                }
            })
        }

        private fun number(): Double {
            val start = offset
            var digits = 0
            while (offset < source.length && source[offset].isDigit()) { offset++; digits++ }
            if (offset < source.length && source[offset] == '.') {
                offset++
                while (offset < source.length && source[offset].isDigit()) { offset++; digits++ }
            }
            if (digits == 0) fail("Expected a number")
            if (offset < source.length && source[offset] in "eE") {
                offset++
                if (offset < source.length && source[offset] in "+-") offset++
                val exponentStart = offset
                while (offset < source.length && source[offset].isDigit()) offset++
                if (offset == exponentStart) fail("Expected an exponent")
            }
            val value = source.substring(start, offset).toDoubleOrNull() ?: fail("Invalid number", start)
            return finite(value)
        }

        private fun finite(value: Double): Double {
            if (!value.isFinite()) fail("Numeric result must be finite")
            return value
        }

        private inline fun nested(block: () -> Double): Double {
            if (++depth > 64) fail("Numeric expression nesting exceeds 64 levels")
            try { return block() } finally { depth-- }
        }

        private fun whitespace() { while (offset < source.length && source[offset].isWhitespace()) offset++ }
        private fun take(character: Char): Boolean {
            whitespace()
            if (offset >= source.length || source[offset] != character) return false
            offset++
            return true
        }
        private fun expect(character: Char) { if (!take(character)) fail("Expected '$character'") }
        private fun fail(reason: String, position: Int = offset): Nothing = throw NumericExpressionException(source, position, reason)
    }
}
