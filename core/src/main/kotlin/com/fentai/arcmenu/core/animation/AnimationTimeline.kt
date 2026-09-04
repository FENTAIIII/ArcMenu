package com.fentai.arcmenu.core.animation

import com.fentai.arcmenu.core.model.Transform
import com.fentai.arcmenu.core.model.Vec3

/** Pure per-session timeline. Bukkit owns the single ticker and applies only changed snapshots. */
class AnimationTimeline(
    private val binding: MenuAnimationBinding,
    private val baseTransforms: Map<String, Transform>,
    initialTransition: TransitionKind?,
) {
    private data class Owner(val target: String, val property: TrackProperty)
    private data class ActiveTrack(val track: AnimationTrack, var elapsed: Long = 0)
    private data class ActiveTransition(val kind: TransitionKind, val phase: TransitionPhase, var elapsed: Int = 0)

    private val active = linkedMapOf<Owner, ActiveTrack>()
    private var transition = initialTransition?.let(::transitionFor)?.let { ActiveTransition(initialTransition, it) }

    init {
        binding.tracks.values.filter { it.trigger == TrackTrigger.OPEN }.forEach(::start)
    }

    fun initial(): TimelineTick = TimelineTick(snapshot(), transition != null)

    fun advance(): TimelineTick {
        val currentTransition = transition
        if (currentTransition?.kind == TransitionKind.EXIT && currentTransition.elapsed >= currentTransition.phase.durationTicks) {
            return TimelineTick(snapshot(), transitionActive = false, exitFinished = true)
        }
        active.values.forEach { it.elapsed++ }
        if (currentTransition != null) {
            currentTransition.elapsed++
            if (currentTransition.kind != TransitionKind.EXIT && currentTransition.elapsed >= currentTransition.phase.durationTicks) {
                transition = null
            }
        }
        return TimelineTick(snapshot(), transition != null)
    }

    fun beginExit(): Boolean {
        active.clear()
        val phase = binding.transition?.exit ?: return false
        transition = ActiveTransition(TransitionKind.EXIT, phase)
        return true
    }

    fun play(trackId: String): Boolean {
        val track = binding.tracks[trackId] ?: return false
        start(track)
        return true
    }

    fun stop(trackId: String): Boolean {
        val owners = active.filterValues { it.track.id == trackId }.keys
        owners.forEach(active::remove)
        return owners.isNotEmpty()
    }

    fun owns(target: String, property: TrackProperty): Boolean = Owner(target, property) in active
    fun trackIds(): Set<String> = binding.tracks.keys

    private fun start(track: AnimationTrack) {
        active[Owner(track.target, track.property)] = ActiveTrack(track)
    }

    private fun transitionFor(kind: TransitionKind): TransitionPhase? = when (kind) {
        TransitionKind.ENTER -> binding.transition?.enter
        TransitionKind.SWITCH -> binding.transition?.switch
        TransitionKind.EXIT -> binding.transition?.exit
    }

    private fun snapshot(): AnimationSnapshot {
        val transforms = linkedMapOf<String, Transform>()
        val contents = linkedMapOf<String, String>()
        val opacities = linkedMapOf<String, Int>()
        active.values.forEach { run ->
            val track = run.track
            val progress = progress(run)
            when (track.property) {
                TrackProperty.OFFSET -> {
                    val value = vector(track, progress)
                    transforms[track.target] = (transforms[track.target] ?: base(track.target)).copy(offset = value)
                }
                TrackProperty.ROTATION -> {
                    val value = vector(track, progress)
                    transforms[track.target] = (transforms[track.target] ?: base(track.target)).copy(rotation = value)
                }
                TrackProperty.SCALE -> {
                    val value = scale(track, progress)
                    transforms[track.target] = (transforms[track.target] ?: base(track.target)).copy(scaleX = value.x, scaleY = value.y)
                }
                TrackProperty.CONTENT -> contents[track.target] = content(track, progress)
                TrackProperty.OPACITY -> opacities[track.target] = opacity(track, progress)
            }
        }
        return AnimationSnapshot(root(), transforms, contents, opacities)
    }

    private fun root(): Transform {
        val running = transition ?: return Transform()
        val phase = running.phase
        val raw = running.elapsed.toDouble() / phase.durationTicks
        val amount = phase.easing.apply(raw)
        val poseAmount = if (running.kind == TransitionKind.EXIT) amount else 1.0 - amount
        return Transform(
            offset = lerp(Vec3(), phase.pose.offset, poseAmount),
            scaleX = lerp(1.0, phase.pose.scaleX, poseAmount),
            scaleY = lerp(1.0, phase.pose.scaleY, poseAmount),
        )
    }

    private fun progress(run: ActiveTrack): Double {
        val duration = run.track.durationTicks.toLong()
        val normalized = when (run.track.loop) {
            TrackLoop.ONCE -> run.elapsed.coerceAtMost(duration).toDouble() / duration
            TrackLoop.REPEAT -> (run.elapsed % duration).toDouble() / duration
            TrackLoop.PING_PONG -> {
                val position = run.elapsed % (duration * 2)
                (if (position <= duration) position else duration * 2 - position).toDouble() / duration
            }
        }
        return run.track.easing.apply(normalized)
    }

    private fun vector(track: AnimationTrack, progress: Double): Vec3 {
        val (left, right, amount) = segment(track, progress)
        return lerp((left.value as VectorFrameValue).value, (right.value as VectorFrameValue).value, amount)
    }

    private fun scale(track: AnimationTrack, progress: Double): ScaleFrameValue {
        val (left, right, amount) = segment(track, progress)
        val a = left.value as ScaleFrameValue
        val b = right.value as ScaleFrameValue
        return ScaleFrameValue(lerp(a.x, b.x, amount), lerp(a.y, b.y, amount))
    }

    private fun opacity(track: AnimationTrack, progress: Double): Int {
        val (left, right, amount) = segment(track, progress)
        val a = (left.value as OpacityFrameValue).value
        val b = (right.value as OpacityFrameValue).value
        return lerp(a.toDouble(), b.toDouble(), amount).toInt().coerceIn(0, 255)
    }

    private fun content(track: AnimationTrack, progress: Double): String =
        track.keyframes.lastOrNull { it.at <= progress }?.value.let { (it as? ContentFrameValue)?.value }
            ?: (track.keyframes.first().value as ContentFrameValue).value

    private data class Segment(val left: AnimationKeyframe, val right: AnimationKeyframe, val amount: Double)

    private fun segment(track: AnimationTrack, progress: Double): Segment {
        val rightIndex = track.keyframes.indexOfFirst { it.at >= progress }.let { if (it < 0) track.keyframes.lastIndex else it }
        val leftIndex = (rightIndex - 1).coerceAtLeast(0)
        val left = track.keyframes[leftIndex]
        val right = track.keyframes[rightIndex]
        val distance = right.at - left.at
        val amount = if (distance <= 0.0) 0.0 else (progress - left.at) / distance
        return Segment(left, right, amount.coerceIn(0.0, 1.0))
    }

    private fun base(target: String): Transform = requireNotNull(baseTransforms[target]) { "动画目标不存在: $target" }
    private fun lerp(a: Double, b: Double, amount: Double) = a + (b - a) * amount
    private fun lerp(a: Vec3, b: Vec3, amount: Double) = Vec3(lerp(a.x, b.x, amount), lerp(a.y, b.y, amount), lerp(a.z, b.z, amount))
}
