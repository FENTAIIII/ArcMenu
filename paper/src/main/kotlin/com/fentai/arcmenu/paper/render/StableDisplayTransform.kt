package com.fentai.arcmenu.paper.render

import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.abs

/** A deterministic T-R-S decomposition for affine matrices without shear. */
internal data class StableDisplayTransform(
    val translation: Vector3f,
    val rotation: Quaternionf,
    val scale: Vector3f,
)

internal data class InterpolationDecision(val duration: Int, val primed: Boolean)

internal object StableDisplayTransforms {
    fun decompose(matrix: Matrix4f): StableDisplayTransform? {
        if (abs(matrix.m03()) > EPSILON || abs(matrix.m13()) > EPSILON ||
            abs(matrix.m23()) > EPSILON || abs(matrix.m33() - 1.0f) > EPSILON) return null

        val x = Vector3f(matrix.m00(), matrix.m01(), matrix.m02())
        val y = Vector3f(matrix.m10(), matrix.m11(), matrix.m12())
        val z = Vector3f(matrix.m20(), matrix.m21(), matrix.m22())
        var sx = x.length()
        val sy = y.length()
        val sz = z.length()
        if (!sx.isFinite() || !sy.isFinite() || !sz.isFinite() || sx < MIN_SCALE || sy < MIN_SCALE || sz < MIN_SCALE) {
            return null
        }
        x.div(sx)
        y.div(sy)
        z.div(sz)
        if (abs(x.dot(y)) > EPSILON || abs(x.dot(z)) > EPSILON || abs(y.dot(z)) > EPSILON) return null

        val handedness = Vector3f(x).cross(y).dot(z)
        if (abs(abs(handedness) - 1.0f) > EPSILON) return null
        if (handedness < 0.0f) {
            x.negate()
            sx = -sx
        }
        val basis = Matrix3f().setColumn(0, x).setColumn(1, y).setColumn(2, z)
        val rotation = Quaternionf().setFromNormalized(basis).normalize()
        return StableDisplayTransform(
            Vector3f(matrix.m30(), matrix.m31(), matrix.m32()),
            rotation,
            Vector3f(sx, sy, sz),
        )
    }

    fun alignHemisphere(rotation: Quaternionf, previous: Quaternionf?): Quaternionf =
        Quaternionf(rotation).also { if (previous != null && previous.dot(it) < 0.0f) it.mul(-1.0f) }

    /**
     * A newly shown Display may have its spawn metadata and first animated target coalesced by the
     * network. Send that first target without interpolation so the client never interpolates from
     * its default identity orientation to the menu plane.
     */
    fun interpolation(requested: Int, stable: Boolean, primed: Boolean): InterpolationDecision = when {
        !stable -> InterpolationDecision(0, false)
        requested <= 0 -> InterpolationDecision(0, primed)
        !primed -> InterpolationDecision(0, true)
        else -> InterpolationDecision(requested, true)
    }

    private const val EPSILON = 1.0e-4f
    private const val MIN_SCALE = 1.0e-7f
}
