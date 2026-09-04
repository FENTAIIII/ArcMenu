package com.fentai.arcmenu.paper.performance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PerformanceMetricsTest {
    @Test
    fun `logical counters distinguish submitted and suppressed work and reset atomically`() {
        val metrics = PerformanceMetrics()
        metrics.recordTick(2_000)
        metrics.recordTick(6_000)
        metrics.matrixWrite()
        repeat(3) { metrics.matrixSkip() }
        metrics.metadataWrite()
        metrics.metadataSkip()
        metrics.entitySpawn()

        val snapshot = metrics.snapshot()
        assertEquals(2, snapshot.ticks)
        assertEquals(4.0, snapshot.averageTickMicros, 1e-9)
        assertEquals(6.0, snapshot.maxTickMicros, 1e-9)
        assertEquals(75.0, snapshot.matrixSkipPercent, 1e-9)
        assertEquals(50.0, snapshot.metadataSkipPercent, 1e-9)
        assertEquals(1, snapshot.entitySpawns)

        metrics.reset()
        val reset = metrics.snapshot()
        assertEquals(0, reset.ticks)
        assertEquals(0, reset.matrixWrites)
        assertTrue(reset.elapsedSeconds >= 0.0)
    }
}
