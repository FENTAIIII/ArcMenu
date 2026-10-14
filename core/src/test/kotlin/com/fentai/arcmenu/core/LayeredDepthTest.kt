package com.fentai.arcmenu.core

import com.fentai.arcmenu.core.animation.AnimationSnapshot
import com.fentai.arcmenu.core.config.MenuFormatException
import com.fentai.arcmenu.core.config.MenuParser
import com.fentai.arcmenu.core.geometry.MenuPlane
import com.fentai.arcmenu.core.geometry.Ray
import com.fentai.arcmenu.core.model.*
import com.fentai.arcmenu.core.render.SceneCompiler
import org.joml.Matrix4d
import org.joml.Matrix4dc
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LayeredDepthTest {
    @Test fun `shared canvas depth syntax is supported while defaults stay unchanged`() {
        val source = "schema-version: 1\nid: depth\ncanvas: {depth-mode: layered}\n"
        assertEquals(CanvasDepthMode.LAYERED, MenuParser().parse(source).definition.canvas.depthMode)
        assertEquals(CanvasDepthMode.PERSPECTIVE, MenuParser().parse(source.replace("layered", "perspective")).definition.canvas.depthMode)
        assertEquals(CanvasDepthMode.PERSPECTIVE, Canvas().depthMode)
        assertEquals(100.0, Canvas().pixelsPerBlock)
        val error = assertThrows<MenuFormatException> { MenuParser().parse(source.replace("layered", "flat"), "depth.yml") }
        assertTrue(error.message!!.contains("depth.yml.canvas.depth-mode"))
    }

    @Test fun `planar native layers keep their edge position size and depth for offset eyes`() {
        for (yaw in listOf(0.0, .7)) for (pitch in listOf(0.0, -.4)) {
            val plane = plane(Canvas(pixelsPerBlock = 42.7, depthMode = CanvasDepthMode.LAYERED), yaw, pitch)
            val eye = plane.origin + plane.normal * .65 + plane.right * .12 + plane.up * -.08
            for (layerZ in listOf(-6.0, 0.0, 6.0)) {
                val local = Matrix4d().translate(140.0, 60.0, layerZ).rotateZ(.2).scale(1.5, .8, 1.0)
                val raw = model(plane, local)
                val corrected = plane.displayMatrix(raw, eye)
                for (corner in listOf(Vector3d(), Vector3d(-10.0, -5.0, 0.0), Vector3d(10.0, 5.0, 0.0))) {
                    val expected = local.transformPosition(Vector3d(corner))
                    val vertex = corrected.transformPosition(Vector3d(corner))
                    val relative = Vec3(vertex.x, vertex.y, vertex.z)
                    val projected = requireNotNull(plane.project(Ray(eye, plane.origin + relative - eye)))
                    assertEquals(expected.x, projected.x, 1e-8)
                    assertEquals(expected.y, projected.y, 1e-8)
                    assertEquals(layerZ / 42.7, relative.dot(plane.normal), 1e-8)
                }
            }
        }
    }

    @Test fun `group and animation depth use the retained plane and current eye`() {
        val nodes = listOf(GroupNode(NodeProperties("group", Transform(offset = Vec3(100.0, 40.0, 6.0))),
            listOf(RectangleNode(NodeProperties("child", Transform(offset = Vec3(12.0, 5.0, 2.0))), 20.0, 10.0, -1))))
        val initialPlane = plane(Canvas(distance = 8.0, depthMode = CanvasDepthMode.LAYERED))
        val plane = initialPlane.withCanvas(Canvas(pixelsPerBlock = 70.0, distance = 2.0, depthMode = CanvasDepthMode.LAYERED))
        for (tick in 0..3) {
            val animation = AnimationSnapshot(rootTransform = Transform(offset = Vec3(-tick.toDouble(), 2.0, tick.toDouble())),
                nodeTransforms = mapOf("group" to Transform(offset = Vec3(100.0, 40.0, 6.0 + tick), scaleX = 1.4)))
            val primitive = SceneCompiler().compile(nodes, animation).single()
            for (distance in listOf(.65, .9)) {
                val eye = plane.origin + plane.normal * distance + plane.up * .2
                val corrected = plane.displayMatrix(model(plane, primitive.transform), eye)
                val actual = corrected.transformPosition(Vector3d())
                val projected = requireNotNull(plane.project(Ray(eye, plane.origin + Vec3(actual.x, actual.y, actual.z) - eye)))
                val expected = primitive.transform.transformPosition(Vector3d())
                assertEquals(expected.x, projected.x, 1e-8)
                assertEquals(expected.y, projected.y, 1e-8)
            }
        }
    }

    @Test fun `default keeps perspective and layered correction retains internal mesh depth`() {
        val defaultPlane = plane(Canvas())
        val nativeModel = model(defaultPlane, Matrix4d().translate(140.0, 60.0, 6.0))
        assertSame(nativeModel, defaultPlane.displayMatrix(nativeModel, defaultPlane.origin + defaultPlane.normal * 3.0))
        val layered = defaultPlane.withCanvas(defaultPlane.canvas.copy(depthMode = CanvasDepthMode.LAYERED))
        val mesh = model(layered, Matrix4d().translate(40.0, 20.0, 4.0).rotateXYZ(.3, -.4, .7).scale(20.0))
        val corrected = layered.displayMatrix(mesh, layered.origin + layered.normal * 3.0)
        for (point in listOf(Vector3d(), Vector3d(1.0, 1.0, 1.0))) {
            val raw = mesh.transformPosition(Vector3d(point))
            val actual = corrected.transformPosition(Vector3d(point))
            assertEquals(Vec3(raw.x, raw.y, raw.z).dot(layered.normal), Vec3(actual.x, actual.y, actual.z).dot(layered.normal), 1e-8)
        }
        assertSame(mesh, layered.displayMatrix(mesh, layered.origin))
    }

    private fun plane(canvas: Canvas, yaw: Double = 0.0, pitch: Double = 0.0): MenuPlane {
        val rotation = Matrix4d().rotateY(yaw).rotateX(pitch)
        fun axis(x: Double, y: Double, z: Double): Vec3 = rotation.transformDirection(Vector3d(x, y, z)).let { Vec3(it.x, it.y, it.z) }
        return MenuPlane(Vec3(230.25, 70.625, -300.5), axis(1.0, 0.0, 0.0), axis(0.0, 1.0, 0.0), axis(0.0, 0.0, 1.0), canvas)
    }

    private fun model(plane: MenuPlane, local: Matrix4dc): Matrix4d =
        Matrix4d().translate(-plane.origin.x, -plane.origin.y, -plane.origin.z).mul(plane.matrix()).mul(local)
}
