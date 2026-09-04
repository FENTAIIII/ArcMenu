package com.fentai.arcmenu.core

import com.fentai.arcmenu.core.animation.*
import com.fentai.arcmenu.core.config.AnimationCatalog
import com.fentai.arcmenu.core.config.MenuFormatException
import com.fentai.arcmenu.core.config.MenuParser
import com.fentai.arcmenu.core.model.Transform
import com.fentai.arcmenu.core.model.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AnimationCatalogTest {
    private val menu = MenuParser().parse(
        """
        schema-version: 1
        id: animated
        frontend:
          group:
            type: group
            children:
              title: {type: text, content: '&fTitle'}
              model: {type: item, material: minecraft:stone}
        backend: {}
        """.trimIndent(),
    )
    private val menus = mapOf("animated" to menu)

    @Test
    fun `strict catalog binds transition and all supported track properties`() {
        val config = AnimationCatalog().parse(
            """
            schema-version: 1
            transitions:
              slide:
                enter: {duration: 8, easing: ease-out, offset: {x: -30}, scale: {x: 0.8, y: 0.8}}
                exit: {duration: 6, offset: {y: -20}}
            tracks:
              move: &vector
                target: group
                property: offset
                duration: 20
                loop: repeat
                keyframes:
                  - {at: 0, value: {x: 0, y: 0, z: 0}}
                  - {at: 1, value: {x: 10, y: 0, z: 0}}
              rotate:
                <<: *vector
                target: model
                property: rotation
              scale:
                target: model
                property: scale
                duration: 20
                trigger: api
                keyframes:
                  - {at: 0, value: {x: 1, y: 1}}
                  - {at: 1, value: {x: 2, y: 2}}
              content:
                target: title
                property: content
                duration: 10
                keyframes:
                  - {at: 0, value: '&fOne'}
                  - {at: 1, value: '&aTwo'}
              opacity:
                target: title
                property: opacity
                duration: 10
                trigger: api
                keyframes:
                  - {at: 0, value: 32}
                  - {at: 1, value: 255}
            menus:
              animated:
                transition: slide
                tracks: [move, rotate, scale, content, opacity]
            """.trimIndent(), menus,
        )

        val binding = requireNotNull(config.forMenu("animated"))
        assertEquals(5, binding.tracks.size)
        assertEquals(8, binding.transition?.enter?.durationTicks)
        assertEquals(-30.0, binding.transition?.enter?.pose?.offset?.x)
    }

    @Test
    fun `opacity and content reject non-text targets`() {
        val error = assertThrows<MenuFormatException> {
            AnimationCatalog().parse(
                """
                tracks:
                  illegal:
                    target: model
                    property: opacity
                    duration: 10
                    keyframes:
                      - {at: 0, value: 0}
                      - {at: 1, value: 255}
                menus:
                  animated: {tracks: [illegal]}
                """.trimIndent(), menus,
            )
        }
        assertTrue(error.message.orEmpty().contains("只允许文本节点"))
    }

    @Test
    fun `two open tracks cannot own the same target property`() {
        val error = assertThrows<MenuFormatException> {
            AnimationCatalog().parse(
                """
                tracks:
                  first: &track
                    target: group
                    property: offset
                    duration: 10
                    keyframes:
                      - {at: 0, value: {x: 0}}
                      - {at: 1, value: {x: 1}}
                  second: *track
                menus:
                  animated: {tracks: [first, second]}
                """.trimIndent(), menus,
            )
        }
        assertTrue(error.message.orEmpty().contains("同时拥有"))
    }

    @Test
    fun `timeline applies enter transition and releases interaction at identity`() {
        val binding = MenuAnimationBinding(
            transition = MenuTransition(enter = TransitionPhase(2, Easing.LINEAR, TransitionPose(Vec3(-20.0, 0.0, 0.0), 0.5, 0.5))),
        )
        val timeline = AnimationTimeline(binding, emptyMap(), TransitionKind.ENTER)
        assertEquals(-20.0, timeline.initial().snapshot.rootTransform.offset.x)
        assertTrue(timeline.advance().transitionActive)
        val complete = timeline.advance()
        assertFalse(complete.transitionActive)
        assertEquals(0.0, complete.snapshot.rootTransform.offset.x, 1e-9)
        assertEquals(1.0, complete.snapshot.rootTransform.scaleX, 1e-9)
    }

    @Test
    fun `new API track replaces the previous owner and stop restores the base`() {
        fun track(id: String, end: Double) = AnimationTrack(
            id, "node", TrackProperty.OFFSET, 10, Easing.LINEAR, TrackLoop.ONCE, TrackTrigger.API,
            listOf(AnimationKeyframe(0.0, VectorFrameValue(Vec3())), AnimationKeyframe(1.0, VectorFrameValue(Vec3(end, 0.0, 0.0)))),
        )
        val first = track("first", 10.0)
        val second = track("second", 20.0)
        val timeline = AnimationTimeline(MenuAnimationBinding(tracks = mapOf(first.id to first, second.id to second)), mapOf("node" to Transform()), null)

        assertTrue(timeline.play("first"))
        timeline.advance()
        assertTrue(timeline.play("second"))
        assertFalse(timeline.stop("first"))
        assertTrue(timeline.advance().snapshot.nodeTransforms.getValue("node").offset.x > 0.0)
        assertTrue(timeline.stop("second"))
        assertTrue(timeline.advance().snapshot.nodeTransforms.isEmpty())
    }

    @Test
    fun `exit cancels node tracks and reports completion after final pose`() {
        val track = AnimationTrack(
            "loop", "node", TrackProperty.ROTATION, 10, Easing.LINEAR, TrackLoop.REPEAT, TrackTrigger.OPEN,
            listOf(AnimationKeyframe(0.0, VectorFrameValue(Vec3())), AnimationKeyframe(1.0, VectorFrameValue(Vec3(0.0, 360.0, 0.0)))),
        )
        val binding = MenuAnimationBinding(
            MenuTransition(exit = TransitionPhase(1, pose = TransitionPose(Vec3(0.0, -10.0, 0.0)))),
            mapOf(track.id to track),
        )
        val timeline = AnimationTimeline(binding, mapOf("node" to Transform()), null)
        assertTrue(timeline.beginExit())
        assertTrue(timeline.initial().snapshot.nodeTransforms.isEmpty())
        assertFalse(timeline.advance().exitFinished)
        assertTrue(timeline.advance().exitFinished)
    }
}
