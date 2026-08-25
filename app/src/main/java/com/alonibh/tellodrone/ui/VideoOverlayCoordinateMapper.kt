package com.alonibh.tellodrone.ui

import com.alonibh.tellodrone.domain.NormalizedBoundingBox

data class OverlayPixelRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * The fixed-size 960x720 Surface is presented with fill-bounds scaling by the full-size SurfaceView.
 * PixelCopy captures that same complete Surface, so normalized analysis coordinates use the same
 * independent X/Y affine scale into the Compose overlay viewport.
 */
object VideoOverlayCoordinateMapper {
    fun mapFillBounds(
        box: NormalizedBoundingBox,
        overlayWidth: Float,
        overlayHeight: Float,
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
        return OverlayPixelRect(
            left = left * overlayWidth,
            top = top * overlayHeight,
            right = right * overlayWidth,
            bottom = bottom * overlayHeight,
        )
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
