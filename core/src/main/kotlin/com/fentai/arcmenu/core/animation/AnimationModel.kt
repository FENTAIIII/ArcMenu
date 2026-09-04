package com.fentai.arcmenu.core.animation

import com.fentai.arcmenu.core.model.Transform
import com.fentai.arcmenu.core.model.Vec3

enum class Easing {
    LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT;

    fun apply(value: Double): Double {
        val t = value.coerceIn(0.0, 1.0)
        return when (this) {
            LINEAR -> t
            EASE_IN -> t * t
            EASE_OUT -> 1.0 - (1.0 - t) * (1.0 - t)
            EASE_IN_OUT -> if (t < 0.5) 2.0 * t * t else 1.0 - (-2.0 * t + 2.0).let { it * it } / 2.0
        }
    }
}

enum class TrackProperty { OFFSET, ROTATION, SCALE, CONTENT, OPACITY }
enum class TrackLoop { ONCE, REPEAT, PING_PONG }
enum class TrackTrigger { OPEN, API }
enum class TransitionKind { ENTER, SWITCH, EXIT }

data class TransitionPose(
    val offset: Vec3 = Vec3(),
    val scaleX: Double = 1.0,
    val scaleY: Double = 1.0,
)

data class TransitionPhase(
    val durationTicks: Int,
    val easing: Easing = Easing.LINEAR,
    val pose: TransitionPose = TransitionPose(),
)

data class MenuTransition(
    val enter: TransitionPhase? = null,
    val exit: TransitionPhase? = null,
    val switch: TransitionPhase? = null,
)

sealed interface KeyframeValue
data class VectorFrameValue(val value: Vec3) : KeyframeValue
data class ScaleFrameValue(val x: Double, val y: Double) : KeyframeValue
data class ContentFrameValue(val value: String) : KeyframeValue
data class OpacityFrameValue(val value: Int) : KeyframeValue

data class AnimationKeyframe(val at: Double, val value: KeyframeValue)

data class AnimationTrack(
    val id: String,
    val target: String,
    val property: TrackProperty,
    val durationTicks: Int,
    val easing: Easing,
    val loop: TrackLoop,
    val trigger: TrackTrigger,
    val keyframes: List<AnimationKeyframe>,
)

data class MenuAnimationBinding(
    val transition: MenuTransition? = null,
    val tracks: Map<String, AnimationTrack> = emptyMap(),
)

data class AnimationConfiguration(
    val menus: Map<String, MenuAnimationBinding> = emptyMap(),
) {
    fun forMenu(menuId: String): MenuAnimationBinding? = menus[menuId]
}

data class AnimationSnapshot(
    val rootTransform: Transform = Transform(),
    val nodeTransforms: Map<String, Transform> = emptyMap(),
    val textContents: Map<String, String> = emptyMap(),
    val textOpacities: Map<String, Int> = emptyMap(),
)

data class TimelineTick(
    val snapshot: AnimationSnapshot,
    val transitionActive: Boolean,
    val exitFinished: Boolean = false,
)
