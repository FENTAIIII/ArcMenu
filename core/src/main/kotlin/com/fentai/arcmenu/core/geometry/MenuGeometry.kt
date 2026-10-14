package com.fentai.arcmenu.core.geometry

import com.fentai.arcmenu.core.model.*
import org.joml.Matrix4d
import org.joml.Matrix4dc
import kotlin.math.abs

data class Ray(val origin: Vec3, val direction: Vec3)

/** Orthonormal world-space axes; normal faces the viewer. Output uses logical canvas units. */
data class MenuPlane @JvmOverloads constructor(
    val origin: Vec3, val right: Vec3, val up: Vec3, val normal: Vec3, val canvas: Canvas,
    val viewerEye: Vec3? = null,
) {
    /** Reuses the first-open world anchor and axes while adopting the next menu's logical canvas. */
    fun withCanvas(nextCanvas: Canvas) = copy(canvas = nextCanvas)

    fun project(ray: Ray): MenuPoint? {
        val denominator = ray.direction.dot(normal)
        if (!denominator.isFinite() || denominator >= -1e-9) return null
        val distance = (origin - ray.origin).dot(normal) / denominator
        if (!distance.isFinite() || distance < 0.0) return null
        val local = ray.origin + ray.direction * distance - origin
        val point = MenuPoint(local.dot(right) * canvas.pixelsPerBlock, local.dot(up) * canvas.pixelsPerBlock)
        return point.takeIf { it.x.isFinite() && it.y.isFinite() }
    }

    fun toWorld(point: MenuPoint): Vec3 = origin + right * (point.x / canvas.pixelsPerBlock) + up * (point.y / canvas.pixelsPerBlock)

    fun matrix(): Matrix4d = Matrix4d(
        right.x, right.y, right.z, 0.0,
        up.x, up.y, up.z, 0.0,
        normal.x, normal.y, normal.z, 0.0,
        origin.x, origin.y, origin.z, 1.0,
    ).scale(1.0 / canvas.pixelsPerBlock)

    /**
     * Correct planar layers about the actual eye while retaining their native depth. Models use
     * anchor-relative world matrices, including group and animation transforms. Tilted surfaces and
     * three-dimensional meshes keep their internal perspective; the correction uses their origin.
     */
    fun displayMatrix(model: Matrix4dc, eye: Vec3): Matrix4dc {
        if (canvas.depthMode != CanvasDepthMode.LAYERED) return model
        val relativeEye = eye - origin
        val distance = relativeEye.dot(normal)
        if (!distance.isFinite() || distance <= 1e-9) return model
        val depth = Vec3(model.m30(), model.m31(), model.m32()).dot(normal)
        val factor = 1.0 - depth / distance
        val correction = 1.0 - factor
        val lateralEye = relativeEye - normal * distance
        val x = normal.x
        val y = normal.y
        val z = normal.z
        return Matrix4d(
            factor + correction * x * x, correction * y * x, correction * z * x, 0.0,
            correction * x * y, factor + correction * y * y, correction * z * y, 0.0,
            correction * x * z, correction * y * z, factor + correction * z * z, 0.0,
            lateralEye.x * correction, lateralEye.y * correction, lateralEye.z * correction, 1.0,
        ).mul(model)
    }
}

/** Priority, then document order. Frontend visibility/depth/transforms do not participate. */
class HitTester(regions: List<InteractionRegion>) {
    private val ordered = regions.sortedByDescending { it.priority }
    fun hit(point: MenuPoint?): InteractionRegion? = point?.let { p -> ordered.firstOrNull { it.contains(p) } }
}

object Transforms {
    fun local(transform: Transform): Matrix4d = Matrix4d()
        .translate(transform.offset.x, transform.offset.y, transform.offset.z)
        .rotateZ(Math.toRadians(transform.rotation.z))
        .rotateX(Math.toRadians(transform.rotation.x))
        .rotateY(Math.toRadians(transform.rotation.y))
        .scale(transform.scaleX, transform.scaleY, transform.scaleZ)

    fun approximatelySame(a: MenuPoint?, b: MenuPoint?, tolerance: Double = 0.01): Boolean =
        if (a == null || b == null) a == b else abs(a.x - b.x) < tolerance && abs(a.y - b.y) < tolerance
}
