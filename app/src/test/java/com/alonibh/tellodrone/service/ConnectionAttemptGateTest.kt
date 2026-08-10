package com.alonibh.tellodrone.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionAttemptGateTest {
    @Test fun `queued availability cannot activate after cancellation`() {
        val gate = ConnectionAttemptGate()
        var published = false

        assertTrue(gate.begin())
        assertTrue(gate.claimNetwork())
        gate.finish { published = false }

        assertFalse(gate.activate { published = true })
        assertFalse(published)
    }

    @Test fun `only one network callback can activate a request`() {
        val gate = ConnectionAttemptGate()

        assertTrue(gate.begin())
        assertTrue(gate.claimNetwork())
        assertFalse(gate.claimNetwork())
        assertTrue(gate.activate { })
        assertFalse(gate.claimNetwork())
    }
}
