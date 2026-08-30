package com.alonibh.tellodrone.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameQualityGateTest {

    @Test
    fun `completely black frame is rejected as corrupt`() {
        val isCorrupt = FrameQualityGate.isCorruptBlackPixels(100, 100) { _, _ -> 0xFF000000.toInt() }
        assertTrue(isCorrupt)
    }

    @Test
    fun `incident corrupt frame with 95_96 percent black and low luminance is rejected`() {
        val isCorrupt = FrameQualityGate.evaluateCorruptFrame(
            fractionBlack = 0.9596f,
            avgLuminance = 2.05f,
        )
        assertTrue(isCorrupt)
    }

    @Test
    fun `extremely low average luminance below 3_5 is rejected regardless of fraction black`() {
        val isCorrupt = FrameQualityGate.evaluateCorruptFrame(
            fractionBlack = 0.85f,
            avgLuminance = 3.0f,
        )
        assertTrue(isCorrupt)
    }

    @Test
    fun `normal illuminated frame is accepted`() {
        val isCorrupt = FrameQualityGate.isCorruptBlackPixels(100, 100) { _, _ -> 0xFF808080.toInt() }
        assertFalse(isCorrupt)
    }

    @Test
    fun `dim but legitimate indoor scene is accepted`() {
        val isCorrupt = FrameQualityGate.evaluateCorruptFrame(
            fractionBlack = 0.50f,
            avgLuminance = 25.0f,
        )
        assertFalse(isCorrupt)
    }

    @Test
    fun `mostly bright frame with some dark spots is accepted`() {
        val isCorrupt = FrameQualityGate.isCorruptBlackPixels(100, 100) { x, y ->
            if (x < 20 && y < 20) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        assertFalse(isCorrupt)
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
