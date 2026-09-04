package com.fentai.arcmenu.paper.performance

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

data class PerformanceSnapshot(
    val elapsedSeconds: Double,
    val ticks: Long,
    val tickTotalNanos: Long,
    val tickMaxNanos: Long,
    val matrixWrites: Long,
    val matrixSkips: Long,
    val metadataWrites: Long,
    val metadataSkips: Long,
    val entitySpawns: Long,
) {
    val averageTickMicros: Double get() = if (ticks == 0L) 0.0 else tickTotalNanos.toDouble() / ticks / 1_000.0
    val maxTickMicros: Double get() = tickMaxNanos / 1_000.0
    val matrixSkipPercent: Double get() = percentage(matrixSkips, matrixWrites + matrixSkips)
    val metadataSkipPercent: Double get() = percentage(metadataSkips, metadataWrites + metadataSkips)

    private fun percentage(value: Long, total: Long): Double = if (total == 0L) 0.0 else value * 100.0 / total
}

/** Logical work counters only; network bytes and client FPS require an external profiler/client. */
class PerformanceMetrics {
    private var startedAt = System.nanoTime()
    private val ticks = LongAdder()
    private val tickTotal = LongAdder()
    private val tickMax = AtomicLong()
    private val matrixWrites = LongAdder()
    private val matrixSkips = LongAdder()
    private val metadataWrites = LongAdder()
    private val metadataSkips = LongAdder()
    private val entitySpawns = LongAdder()

    fun recordTick(nanos: Long) {
        ticks.increment()
        tickTotal.add(nanos)
        tickMax.accumulateAndGet(nanos, ::maxOf)
    }

    fun matrixWrite() = matrixWrites.increment()
    fun matrixSkip() = matrixSkips.increment()
    fun metadataWrite() = metadataWrites.increment()
    fun metadataSkip() = metadataSkips.increment()
    fun entitySpawn() = entitySpawns.increment()

    fun snapshot(): PerformanceSnapshot = PerformanceSnapshot(
        elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0,
        ticks = ticks.sum(),
        tickTotalNanos = tickTotal.sum(),
        tickMaxNanos = tickMax.get(),
        matrixWrites = matrixWrites.sum(),
        matrixSkips = matrixSkips.sum(),
        metadataWrites = metadataWrites.sum(),
        metadataSkips = metadataSkips.sum(),
        entitySpawns = entitySpawns.sum(),
    )

    fun reset() {
        startedAt = System.nanoTime()
        ticks.reset()
        tickTotal.reset()
        tickMax.set(0L)
        matrixWrites.reset()
        matrixSkips.reset()
        metadataWrites.reset()
        metadataSkips.reset()
        entitySpawns.reset()
    }
}
