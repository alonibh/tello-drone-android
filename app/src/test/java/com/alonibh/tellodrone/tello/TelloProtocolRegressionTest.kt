package com.alonibh.tellodrone.tello

import org.junit.Assert.assertEquals
import org.junit.Test

class TelloProtocolRegressionTest {
    @Test fun `RC command preserves Tello axis ordering`() {
        assertEquals("rc 1 2 3 4", RcVector(1, 2, 3, 4).asCommand())
    }

    @Test fun `RC command preserves full-range signed channel values`() {
        assertEquals("rc 100 -100 100 -100", RcVector(100, -100, 100, -100).asCommand())
        assertEquals("rc -100 100 -100 100", RcVector(-100, 100, -100, 100).asCommand())
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
