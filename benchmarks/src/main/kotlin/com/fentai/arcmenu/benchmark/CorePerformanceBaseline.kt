package com.fentai.arcmenu.benchmark

import com.fentai.arcmenu.core.animation.*
import com.fentai.arcmenu.core.model.*
import com.fentai.arcmenu.core.render.SceneCompiler
import java.lang.management.ManagementFactory
import java.util.Locale

/** Reproducible core-only microbaseline. It does not claim server MSPT, bytes or client FPS. */
object CorePerformanceBaseline {
    @JvmStatic
    fun main(args: Array<String>) {
        Locale.setDefault(Locale.ROOT)
        println("java=${System.getProperty("java.version")},vm=${System.getProperty("java.vm.name")},pid=${ManagementFactory.getRuntimeMXBean().pid}")
        println("nodes,compile_median_us,compile_p95_us,timeline_median_us,timeline_p95_us,checksum")
        val counts = if (args.isEmpty()) listOf(20, 100, 300) else args.map(String::toInt)
        require(counts.all { it in setOf(20, 100, 300) }) { "node count must be 20, 100 or 300" }
        counts.forEach(::measure)
    }

    private fun measure(count: Int) {
        val nodes = List(count) { index ->
            RectangleNode(
                NodeProperties(
                    "node-$index",
                    Transform(offset = Vec3((index % 20) * 9.0, (index / 20) * -7.0, (index % 5) * 0.1)),
                ),
                width = 8.0,
                height = 6.0,
                argb = 0xFF335577.toInt(),
            )
        }
        val compiler = SceneCompiler()
        repeat(WARMUP) { compiler.compile(nodes) }
        var checksum = 0.0
        val compileSamples = LongArray(SAMPLES) {
            val start = System.nanoTime()
            repeat(COMPILE_BATCH) {
                compiler.compile(nodes).forEach { primitive ->
                    checksum += primitive.transform.m00() + primitive.transform.m30() +
                        primitive.transform.m31() + primitive.transform.m32()
                }
            }
            (System.nanoTime() - start) / COMPILE_BATCH
        }

        val track = AnimationTrack(
            "spin", "node-0", TrackProperty.ROTATION, 60, Easing.LINEAR, TrackLoop.REPEAT, TrackTrigger.OPEN,
            listOf(
                AnimationKeyframe(0.0, VectorFrameValue(Vec3())),
                AnimationKeyframe(1.0, VectorFrameValue(Vec3(y = 360.0))),
            ),
        )
        val timelineSamples = LongArray(SAMPLES) {
            val timeline = AnimationTimeline(
                MenuAnimationBinding(tracks = mapOf(track.id to track)),
                mapOf("node-0" to Transform()),
                null,
            )
            val start = System.nanoTime()
            repeat(TIMELINE_BATCH) {
                checksum += timeline.advance().snapshot.nodeTransforms["node-0"]?.rotation?.y ?: 0.0
            }
            (System.nanoTime() - start) / TIMELINE_BATCH
        }
        compileSamples.sort()
        timelineSamples.sort()
        println(String.format(
            Locale.ROOT, "%d,%.3f,%.3f,%.3f,%.3f,%d",
            count,
            compileSamples.medianMicros(), compileSamples.p95Micros(),
            timelineSamples.medianMicros(), timelineSamples.p95Micros(), checksum.toLong(),
        ))
    }

    private fun LongArray.medianMicros() = this[size / 2] / 1_000.0
    private fun LongArray.p95Micros() = this[((size - 1) * 0.95).toInt()] / 1_000.0

    private const val WARMUP = 100
    private const val SAMPLES = 15
    private const val COMPILE_BATCH = 200
    private const val TIMELINE_BATCH = 2_000
}
