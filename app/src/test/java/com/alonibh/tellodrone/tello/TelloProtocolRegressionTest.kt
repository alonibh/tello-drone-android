package com.alonibh.tellodrone.tello

import org.junit.Assert.assertEquals
import org.junit.Test

class TelloProtocolRegressionTest {
    @Test fun `RC command preserves Tello axis ordering`() {
        assertEquals("rc 1 2 3 4", RcVector(1, 2, 3, 4).asCommand())
    }
}
