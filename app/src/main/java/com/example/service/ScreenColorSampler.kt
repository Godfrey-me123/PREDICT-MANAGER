package com.example.service

import android.graphics.Bitmap
import android.graphics.Rect
import com.example.data.model.ColorClassifier
import com.example.data.model.CrashColorCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

data class PixelColorSample(
    val hex: String,
    val category: CrashColorCategory,
    val isPixelVerified: Boolean
)

/**
 * Thread-safe screen color sampler backed by optional user-consented MediaProjection frames.
 * Reads actual foreground badge/pill pixels inside node bounding rectangles without ever blocking UI.
 */
object ScreenColorSampler {
    private val latestFrameRef = AtomicReference<Bitmap?>(null)

    private val _isCaptureActive = MutableStateFlow(false)
    val isCaptureActive: StateFlow<Boolean> = _isCaptureActive.asStateFlow()

    private val _lastCaptureError = MutableStateFlow<String?>(null)
    val lastCaptureError: StateFlow<String?> = _lastCaptureError.asStateFlow()

    fun setCaptureActive(active: Boolean, error: String? = null) {
        _isCaptureActive.value = active
        _lastCaptureError.value = error
        if (!active) {
            updateFrame(null)
        }
    }

    fun updateFrame(bitmap: Bitmap?) {
        val old = latestFrameRef.getAndSet(bitmap)
        if (old != null && old !== bitmap && !old.isRecycled) {
            runCatching { old.recycle() }
        }
    }

    /**
     * Samples the dominant vivid non-background color inside [bounds] on the latest captured frame.
     * Falls back to interior center pixels if dark/light grayscale frame dominates.
     */
    fun sampleColorInBounds(bounds: Rect?): PixelColorSample? {
        if (bounds == null || bounds.isEmpty) return null
        val bmp = latestFrameRef.get() ?: return null
        if (bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) return null

        val left = bounds.left.coerceIn(0, bmp.width - 1)
        val right = bounds.right.coerceIn(left + 1, bmp.width)
        val top = bounds.top.coerceIn(0, bmp.height - 1)
        val bottom = bounds.bottom.coerceIn(top + 1, bmp.height)
        val w = right - left
        val h = bottom - top
        if (w < 2 || h < 2) return null

        val stepX = (w / 6).coerceAtLeast(1)
        val stepY = (h / 4).coerceAtLeast(1)

        var bestVividArgb: Int? = null
        var bestVividScore = -1f
        val categoryCounts = mutableMapOf<CrashColorCategory, Int>()
        val representativeByCat = mutableMapOf<CrashColorCategory, Int>()

        var y = top + (h / 6)
        while (y < bottom - (h / 6).coerceAtLeast(1)) {
            var x = left + (w / 8)
            while (x < right - (w / 8).coerceAtLeast(1)) {
                val pixel = try {
                    bmp.getPixel(x, y)
                } catch (_: Throwable) {
                    x += stepX
                    continue
                }
                val r = ((pixel ushr 16) and 0xFF) / 255f
                val g = ((pixel ushr 8) and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f
                val maxC = maxOf(r, g, b)
                val minC = minOf(r, g, b)
                val sat = if (maxC <= 0f) 0f else (maxC - minC) / maxC

                val cat = ColorClassifier.classifyArgb(pixel)
                if (cat != CrashColorCategory.Other) {
                    categoryCounts[cat] = (categoryCounts[cat] ?: 0) + 1
                    representativeByCat.putIfAbsent(cat, pixel)
                    val score = sat * maxC
                    if (score > bestVividScore) {
                        bestVividScore = score
                        bestVividArgb = pixel
                    }
                }
                x += stepX
            }
            y += stepY
        }

        val dominantCategory = categoryCounts.maxByOrNull { it.value }?.key
        val chosenArgb = representativeByCat[dominantCategory] ?: bestVividArgb ?: return null
        val finalCat = ColorClassifier.classifyArgb(chosenArgb)
        return PixelColorSample(
            hex = ColorClassifier.formatArgbHex(chosenArgb),
            category = finalCat,
            isPixelVerified = true
        )
    }
}
