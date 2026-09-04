package com.fentai.arcmenu.paper.render

import org.joml.Matrix4f
import org.joml.Quaternionf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StableDisplayTransformTest {
    @Test
    fun `transition scale keeps one rotation decomposition through identity`() {
        val plane = Matrix4f().rotateXYZ(0.37f, -0.81f, 0.23f).scale(0.01f)
        var previous: Quaternionf? = null
        for (tick in 0..8) {
            val amount = 0.9f + tick / 80.0f
            val matrix = Matrix4f(plane).scale(amount, amount, 1.0f)
            val decomposed = requireNotNull(StableDisplayTransforms.decompose(matrix))
            val rotation = StableDisplayTransforms.alignHemisphere(decomposed.rotation, previous)
            val rebuilt = Matrix4f().translation(decomposed.translation)
                .rotate(rotation).scale(decomposed.scale)

            assertTrue(matrix.equals(rebuilt, 1.0e-5f), "decomposition must preserve the authored matrix")
            if (previous != null) assertTrue(previous.dot(rotation) > 0.99999f, "scale must not inject rotation")
            previous = rotation
        }
    }

    @Test
    fun `quaternion remains continuous across a repeating full rotation`() {
        var previous: Quaternionf? = null
        for (cycle in 0..1) {
            for (degrees in 0..360 step 6) {
                val matrix = Matrix4f().rotateY(Math.toRadians(degrees.toDouble()).toFloat()).scale(2.0f, 3.0f, 4.0f)
                val decomposed = requireNotNull(StableDisplayTransforms.decompose(matrix))
                val rotation = StableDisplayTransforms.alignHemisphere(decomposed.rotation, previous)
                if (previous != null) assertTrue(previous.dot(rotation) > 0.99f, "adjacent frames must use one quaternion hemisphere")
                previous = rotation
            }
        }
    }

    @Test
    fun `sheared matrix is left to exact matrix fallback`() {
        val shear = Matrix4f().identity().m10(0.25f)
        assertNull(StableDisplayTransforms.decompose(shear))
    }

    @Test
    fun `first animated target primes client before interpolation`() {
        val initial = StableDisplayTransforms.interpolation(requested = 0, stable = true, primed = false)
        val firstTarget = StableDisplayTransforms.interpolation(requested = 1, stable = true, primed = initial.primed)
        val secondTarget = StableDisplayTransforms.interpolation(requested = 1, stable = true, primed = firstTarget.primed)
        val shearFallback = StableDisplayTransforms.interpolation(requested = 1, stable = false, primed = secondTarget.primed)

        assertTrue(initial.duration == 0 && !initial.primed)
        assertTrue(firstTarget.duration == 0 && firstTarget.primed)
        assertTrue(secondTarget.duration == 1 && secondTarget.primed)
        assertTrue(shearFallback.duration == 0 && !shearFallback.primed)
    }
}
