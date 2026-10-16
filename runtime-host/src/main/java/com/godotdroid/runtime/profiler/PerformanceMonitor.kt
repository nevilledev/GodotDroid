package com.godotdroid.runtime.profiler

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * Real-time performance profiler monitoring FPS, frame render time, and native + Java RAM usage.
 * Uses Choreographer.FrameCallback for hardware VSYNC-aligned sampling.
 */
class PerformanceMonitor(
    private val onMetricsUpdated: ((PerformanceMetrics) -> Unit)? = null
) : Choreographer.FrameCallback {

    data class PerformanceMetrics(
        val fps: Int = 0,
        val frameTimeMs: Float = 0f,
        val ramMb: Long = 0L
    ) {
        val formattedSummary: String
            get() = "$fps FPS • ${String.format(java.util.Locale.US, "%.1f", frameTimeMs)} ms • $ramMb MB RAM"
    }

    private val _metrics = MutableStateFlow(PerformanceMetrics())
    val metrics: StateFlow<PerformanceMetrics> = _metrics.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isRunning = false

    private var lastFrameNanos: Long = 0L
    private var lastMetricsSampleNanos: Long = 0L
    private var frameCount: Int = 0
    private var accumulatedFrameTimeMs: Float = 0f

    fun start() {
        if (isRunning) return
        isRunning = true
        mainHandler.post {
            lastMetricsSampleNanos = System.nanoTime()
            lastFrameNanos = 0L
            frameCount = 0
            accumulatedFrameTimeMs = 0f
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun stop() {
        isRunning = false
        mainHandler.post {
            Choreographer.getInstance().removeFrameCallback(this)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRunning) return

        if (lastFrameNanos > 0L) {
            val deltaNanos = frameTimeNanos - lastFrameNanos
            val frameTimeMs = deltaNanos / 1_000_000f
            accumulatedFrameTimeMs += frameTimeMs
            frameCount++
        }
        lastFrameNanos = frameTimeNanos

        val now = System.nanoTime()
        val elapsedNanos = now - lastMetricsSampleNanos
        if (elapsedNanos >= 1_000_000_000L) { // 1 second sample window
            val fps = if (elapsedNanos > 0) {
                ((frameCount * 1_000_000_000.0) / elapsedNanos).roundToInt()
            } else 0

            val avgFrameTimeMs = if (frameCount > 0) {
                accumulatedFrameTimeMs / frameCount
            } else 0f

            // RAM Profiling (Native Heap + Dalvik/ART Heap)
            val nativeHeapMb = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
            val runtime = Runtime.getRuntime()
            val javaHeapMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            val totalRamMb = (nativeHeapMb + javaHeapMb).coerceAtLeast(1L)

            val updated = PerformanceMetrics(
                fps = fps,
                frameTimeMs = avgFrameTimeMs,
                ramMb = totalRamMb
            )

            _metrics.value = updated
            onMetricsUpdated?.invoke(updated)

            // Reset sample counters
            frameCount = 0
            accumulatedFrameTimeMs = 0f
            lastMetricsSampleNanos = now
        }

        Choreographer.getInstance().postFrameCallback(this)
    }
}
