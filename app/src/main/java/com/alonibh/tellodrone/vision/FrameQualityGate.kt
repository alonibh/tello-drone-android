package com.alonibh.tellodrone.vision

import android.graphics.Bitmap

data class FrameQualityMetrics(
    val isCorrupt: Boolean,
    val blackPixelFraction: Float,
    val averageLuminance: Float,
)

/**
 * Lightweight quality gate to reject corrupt/black analysis frames before inference.
 * Rejection preserves prior target observation without creating false TemporarilyMissing state.
 */
object FrameQualityGate {
    const val SAMPLE_STEP_X = 10
    const val SAMPLE_STEP_Y = 10
    const val NEAR_BLACK_LUMINANCE_THRESHOLD = 10f
    const val FRACTION_BLACK_THRESHOLD = 0.96f
    const val AVERAGE_LUMINANCE_THRESHOLD = 6.0f
    const val MAX_CONSECUTIVE_CORRUPT_FRAMES = 3

    fun analyze(bitmap: Bitmap): FrameQualityMetrics {
        return analyzePixels(bitmap.width, bitmap.height) { x, y -> bitmap.getPixel(x, y) }
    }

    fun isCorruptBlackFrame(bitmap: Bitmap): Boolean = analyze(bitmap).isCorrupt

    fun analyzePixels(width: Int, height: Int, getPixel: (x: Int, y: Int) -> Int): FrameQualityMetrics {
        if (width <= 0 || height <= 0) return FrameQualityMetrics(true, 1.0f, 0.0f)
        var blackCount = 0
        var totalCount = 0
        var totalLuminance = 0.0

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val pixel = getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                totalLuminance += lum
                if (lum < NEAR_BLACK_LUMINANCE_THRESHOLD) {
                    blackCount++
                }
                totalCount++
                x += SAMPLE_STEP_X
            }
            y += SAMPLE_STEP_Y
        }

        if (totalCount == 0) return FrameQualityMetrics(true, 1.0f, 0.0f)
        val fractionBlack = blackCount.toFloat() / totalCount
        val avgLuminance = (totalLuminance / totalCount).toFloat()
        val isCorrupt = fractionBlack >= FRACTION_BLACK_THRESHOLD && avgLuminance <= AVERAGE_LUMINANCE_THRESHOLD
        return FrameQualityMetrics(isCorrupt, fractionBlack, avgLuminance)
    }

    fun isCorruptBlackPixels(width: Int, height: Int, getPixel: (x: Int, y: Int) -> Int): Boolean {
        return analyzePixels(width, height, getPixel).isCorrupt
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
