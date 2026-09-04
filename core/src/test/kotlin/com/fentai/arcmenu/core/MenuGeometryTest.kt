package com.fentai.arcmenu.core

import com.fentai.arcmenu.core.animation.AnimationSnapshot
import com.fentai.arcmenu.core.geometry.*
import com.fentai.arcmenu.core.model.*
import com.fentai.arcmenu.core.render.Quad
import com.fentai.arcmenu.core.render.SceneCompiler
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.math.cos
import kotlin.math.sin

class MenuGeometryTest {
    @ParameterizedTest @ValueSource(doubles = [0.0, 15.0, 90.0, 180.0, 270.0])
    fun `world rays project to known canvas point at different menu orientations`(degrees: Double) {
        val angle = Math.toRadians(degrees)
        val normal = Vec3(sin(angle), 0.0, cos(angle))
        val right = Vec3(cos(angle), 0.0, -sin(angle))
        val up = Vec3(0.0, 1.0, 0.0)
        val origin = Vec3(180.0, 70.0, -315.0)
        val plane = MenuPlane(origin, right, up, normal, Canvas(pixelsPerBlock = 100.0))
        val observer = origin + normal * 3.0
        // Independently specified world offset: right 0.7 blocks and up 0.3 blocks.
        val target = origin + right * 0.7 + up * 0.3
        val point = requireNotNull(plane.project(Ray(observer, target - observer)))
        assertEquals(70.0, point.x, 1e-8)
        assertEquals(30.0, point.y, 1e-8)
        assertEquals(target.x, plane.toWorld(point).x, 1e-8)
        val transformed = plane.matrix().transformPosition(Vector3d(70.0, 30.0, 0.0))
        assertEquals(target.x, transformed.x, 1e-8)
        assertEquals(target.y, transformed.y, 1e-8)
        assertEquals(target.z, transformed.z, 1e-8)
    }

    @Test fun `parallel backward rear-face and non-finite rays do not hit`() {
        val plane = MenuPlane(Vec3(), Vec3(1.0), Vec3(y = 1.0), Vec3(z = 1.0), Canvas())
        assertNull(plane.project(Ray(Vec3(z = 3.0), Vec3(x = 1.0))))
        assertNull(plane.project(Ray(Vec3(z = 3.0), Vec3(z = 1.0))))
        assertNull(plane.project(Ray(Vec3(z = -3.0), Vec3(z = -1.0))))
        assertNull(plane.project(Ray(Vec3(z = -3.0), Vec3(z = 1.0))))
        assertNull(plane.project(Ray(Vec3(z = 3.0), Vec3(z = Double.NaN))))
    }

    @Test fun `priority beats order and tied priority preserves document order`() {
        val low = InteractionRegion("low", 0.0, 0.0, 20.0, 20.0)
        val first = low.copy(id = "first", priority = 2)
        val last = low.copy(id = "last", priority = 2)
        val hit = HitTester(listOf(low, first, last))
        assertEquals("first", hit.hit(MenuPoint(10.0, -10.0))?.id)
        assertNull(hit.hit(MenuPoint(10.01, -10.0)))
        assertNull(hit.hit(null))
    }

    @Test fun `hidden frontend groups produce no primitives but do not hide backend`() {
        val rect = RectangleNode(NodeProperties("rect"), 10.0, 10.0, -1)
        val group = GroupNode(NodeProperties("hidden", visible = false), listOf(rect))
        assertTrue(SceneCompiler().compile(listOf(group)).isEmpty())
        assertNotNull(HitTester(listOf(InteractionRegion("button", 0.0, 0.0, 10.0, 10.0))).hit(MenuPoint(0.0, 0.0)))
    }

    @Test fun `z offset uses logical units instead of whole world blocks`() {
        val origin = Vec3(10.0, 20.0, 30.0)
        val plane = MenuPlane(origin, Vec3(x = 1.0), Vec3(y = 1.0), Vec3(z = 1.0), Canvas(pixelsPerBlock = 100.0))
        val transformed = plane.matrix().transformPosition(Vector3d(0.0, 0.0, 1.0))
        assertEquals(10.0, transformed.x, 1e-8)
        assertEquals(20.0, transformed.y, 1e-8)
        assertEquals(30.01, transformed.z, 1e-8)
    }

    @Test fun `menu route keeps first world plane while adopting next canvas`() {
        val first = MenuPlane(
            Vec3(10.0, 20.0, 30.0), Vec3(x = 1.0), Vec3(y = 1.0), Vec3(z = 1.0),
            Canvas(width = 320.0, height = 180.0, pixelsPerBlock = 100.0, distance = 3.0),
        )
        val secondCanvas = Canvas(width = 400.0, height = 240.0, pixelsPerBlock = 80.0, distance = 8.0)
        val routed = first.withCanvas(secondCanvas)

        assertEquals(first.origin, routed.origin)
        assertEquals(first.right, routed.right)
        assertEquals(first.up, routed.up)
        assertEquals(first.normal, routed.normal)
        assertEquals(secondCanvas, routed.canvas)
    }

    @Test fun `frame matches real border proportional calibration`() {
        val frame = FrameNode(NodeProperties("border"), 110.0, 55.0, 1.0, -1)
        val primitives = SceneCompiler().compile(listOf(frame)).map { it as Quad }
        assertEquals(4, primitives.size)

        val positions = primitives.associate { primitive ->
            primitive.id to primitive.transform.transformPosition(Vector3d())
        }
        assertEquals(27.78, positions.getValue("border/top").y, 1e-8)
        assertEquals(-27.78, positions.getValue("border/bottom").y, 1e-8)
        assertEquals(-55.34, positions.getValue("border/left").x, 1e-8)
        assertEquals(55.55, positions.getValue("border/right").x, 1e-8)

        assertEquals(110.0, primitives[0].width, 1e-8)
        assertEquals(55.0, primitives[2].width, 1e-8)
        assertEquals(1.0, primitives[2].height, 1e-8)
        val verticalDirection = primitives[2].transform.transformDirection(Vector3d(1.0, 0.0, 0.0))
        assertEquals(0.0, verticalDirection.x, 1e-8)
        assertEquals(1.0, verticalDirection.y, 1e-8)
    }

    @Test fun `frame edge positions scale proportionally from real border one`() {
        val frame = FrameNode(NodeProperties("border"), 100.0, 55.0, 0.1, -1)
        val primitives = SceneCompiler().compile(listOf(frame)).map { it as Quad }
        val positions = primitives.associate { it.id to it.transform.transformPosition(Vector3d()) }
        assertEquals(-55.34 * 100.0 / 110.0, positions.getValue("border/left").x, 1e-8)
        assertEquals(55.55 * 100.0 / 110.0, positions.getValue("border/right").x, 1e-8)
        assertEquals(27.78, positions.getValue("border/top").y, 1e-8)
    }

    @Test fun `item and block scale derive model depth from smaller 2D axis`() {
        val transform = Transform(scaleX = 40.0, scaleY = 30.0)
        val properties = NodeProperties("model", transform)
        val item = ItemNode(properties, "minecraft:diamond_sword")
        val block = BlockNode(properties.copy(id = "block"), "minecraft:stone")
        val primitives = SceneCompiler().compile(listOf(item, block))
        for (primitive in primitives) {
            val direction = primitive.transform.transformDirection(Vector3d(0.0, 0.0, 1.0))
            assertEquals(30.0, direction.z, 1e-8)
        }
    }

    @Test fun `animation root and group transforms affect frontend descendants`() {
        val child = RectangleNode(
            NodeProperties("child", Transform(offset = Vec3(x = 2.0))),
            10.0, 10.0, -1,
        )
        val group = GroupNode(
            NodeProperties("group", Transform(offset = Vec3(x = 5.0))),
            listOf(child),
        )
        val animation = AnimationSnapshot(
            rootTransform = Transform(offset = Vec3(x = -3.0)),
            nodeTransforms = mapOf("group" to Transform(offset = Vec3(x = 20.0))),
        )

        val primitive = SceneCompiler().compile(listOf(group), animation).single()
        val position = primitive.transform.transformPosition(Vector3d())
        assertEquals(19.0, position.x, 1e-8)
        assertEquals(0.0, position.y, 1e-8)
    }
}
