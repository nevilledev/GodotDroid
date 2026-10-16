package com.godotdroid.runtime.profiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceMonitorTest {

    @Test
    fun testPerformanceMetricsFormatting() {
        val metrics = PerformanceMonitor.PerformanceMetrics(
            fps = 60,
            frameTimeMs = 16.666f,
            ramMb = 184L
        )

        val summary = metrics.formattedSummary
        assertTrue("Summary should contain FPS", summary.contains("60 FPS"))
        assertTrue("Summary should contain frame time", summary.contains("16.7 ms") || summary.contains("16.6 ms"))
        assertTrue("Summary should contain RAM", summary.contains("184 MB RAM"))
    }

    @Test
    fun testPerformanceMetricsZeroState() {
        val defaultMetrics = PerformanceMonitor.PerformanceMetrics()
        assertEquals(0, defaultMetrics.fps)
        assertEquals(0f, defaultMetrics.frameTimeMs, 0.001f)
        assertEquals(0L, defaultMetrics.ramMb)
        assertEquals("0 FPS • 0.0 ms • 0 MB RAM", defaultMetrics.formattedSummary)
    }
}
