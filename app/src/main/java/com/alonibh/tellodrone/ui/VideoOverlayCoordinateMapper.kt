package com.alonibh.tellodrone.ui

import com.alonibh.tellodrone.domain.NormalizedBoundingBox

data class OverlayPixelRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

data class VideoContentFrame(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

/**
 * The decoded surface and every detection overlay share this centered aspect-fit transform. This
 * deliberately permits letterboxing rather than stretching or cropping the 4:3 Tello image.
 */
object VideoOverlayCoordinateMapper {
    fun aspectFitFrame(
        containerWidth: Float,
        containerHeight: Float,
        sourceWidth: Float,
        sourceHeight: Float,
    ): VideoContentFrame {
        if (!containerWidth.isFinite() || !containerHeight.isFinite() ||
            !sourceWidth.isFinite() || !sourceHeight.isFinite() ||
            containerWidth <= 0f || containerHeight <= 0f || sourceWidth <= 0f || sourceHeight <= 0f
        ) return VideoContentFrame(0f, 0f, 0f, 0f)
        val scale = minOf(containerWidth / sourceWidth, containerHeight / sourceHeight)
        val width = sourceWidth * scale
        val height = sourceHeight * scale
        return VideoContentFrame(
            left = (containerWidth - width) / 2f,
            top = (containerHeight - height) / 2f,
            width = width,
            height = height,
        )
    }

    fun mapAspectFit(
        box: NormalizedBoundingBox,
        overlayWidth: Float,
        overlayHeight: Float,
        sourceWidth: Float,
        sourceHeight: Float,
    ): OverlayPixelRect? {
        if (!overlayWidth.isFinite() || !overlayHeight.isFinite() || overlayWidth <= 0f || overlayHeight <= 0f) {
            return null
        }
        val values = listOf(box.left, box.top, box.right, box.bottom)
        if (values.any { !it.isFinite() }) return null
        val left = box.left.coerceIn(0f, 1f)
        val top = box.top.coerceIn(0f, 1f)
        val right = box.right.coerceIn(0f, 1f)
        val bottom = box.bottom.coerceIn(0f, 1f)
        if (right <= left || bottom <= top) return null
        val frame = aspectFitFrame(overlayWidth, overlayHeight, sourceWidth, sourceHeight)
        if (frame.width <= 0f || frame.height <= 0f) return null
        return OverlayPixelRect(
            left = frame.left + left * frame.width,
            top = frame.top + top * frame.height,
            right = frame.left + right * frame.width,
            bottom = frame.top + bottom * frame.height,
        )
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
