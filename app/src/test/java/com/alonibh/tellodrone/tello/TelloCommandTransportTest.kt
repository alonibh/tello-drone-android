@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.alonibh.tellodrone.tello

import java.net.SocketTimeoutException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelloCommandTransportTest {
    @Test fun `Tello control endpoint uses the SDK reply port locally`() {
        assertEquals(NetworkTelloTransport.COMMAND_PORT, NetworkTelloTransport.CONTROL_LOCAL_PORT)
    }

    @Test fun `blocking commands are serialized`() = runTest {
        val endpoint = FakeEndpoint()
        val transport = SerializedTelloCommandTransport(endpoint)

        val first = async { transport.sendCommand("command", 1_000) }
        endpoint.sentSignal.receive()
        val second = async { transport.sendCommand("takeoff", 1_000) }
        runCurrent()
        assertEquals(listOf("command"), endpoint.sent)

        endpoint.responses.send("ok")
        assertTrue(first.await() is TelloCommandResult.Success)
        endpoint.sentSignal.receive()
        assertEquals(listOf("command", "takeoff"), endpoint.sent)
        endpoint.responses.send("ok")
        assertTrue(second.await() is TelloCommandResult.Success)
    }

    @Test fun `blocking command times out`() = runTest {
        val endpoint = FakeEndpoint(timeoutOnEmpty = true)
        var currentTime = 0L
        val clock = MonotonicClock { currentTime }
        val transport = SerializedTelloCommandTransport(endpoint, clock)

        val commandJob = async { transport.sendCommand("command", timeoutMillis = 100) }
        endpoint.sentSignal.receive()
        // Advance clock past deadline
        currentTime = 150L
        val result = commandJob.await()
        assertEquals(TelloCommandResult.Timeout, result)
    }

    @Test fun `garbage then ok returns Success`() = runTest {
        val endpoint = FakeEndpoint()
        var discarded = 0
        val transport = SerializedTelloCommandTransport(endpoint, onDiscardedResponse = { discarded++ })

        val cmd = async { transport.sendCommand("command", 1_000) }
        endpoint.sentSignal.receive()

        endpoint.responses.send("pitch:0;roll:0;bat:90;") // unrelated telemetry on command port
        endpoint.responses.send("random router noise")
        endpoint.responses.send("ok")

        val result = cmd.await()
        assertTrue(result is TelloCommandResult.Success)
        assertEquals("ok", (result as TelloCommandResult.Success).response)
        assertEquals(2, discarded)
        assertEquals(2L, transport.discardedResponsesCount)
    }

    @Test fun `non-printable bytes then ok returns Success`() = runTest {
        val endpoint = FakeEndpoint()
        var discarded = 0
        val transport = SerializedTelloCommandTransport(endpoint, onDiscardedResponse = { discarded++ })

        val cmd = async { transport.sendCommand("command", 1_000) }
        endpoint.sentSignal.receive()

        endpoint.responses.send(String(byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte())))
        endpoint.responses.send("OK\r\n")

        val result = cmd.await()
        assertTrue(result is TelloCommandResult.Success)
        assertEquals("OK", (result as TelloCommandResult.Success).response)
        assertEquals(1, discarded)
    }

    @Test fun `garbage repeatedly until deadline returns Timeout`() = runTest {
        val endpoint = FakeEndpoint()
        var currentTime = 1_000L
        val clock = MonotonicClock { currentTime }
        val transport = SerializedTelloCommandTransport(endpoint, clock)

        val cmd = async { transport.sendCommand("command", timeoutMillis = 200) }
        endpoint.sentSignal.receive()

        // Send garbage while advancing clock
        currentTime = 1_050L
        endpoint.responses.send("garbage 1")
        runCurrent()
        currentTime = 1_150L
        endpoint.responses.send("garbage 2")
        runCurrent()
        currentTime = 1_250L // past deadline of 1200L

        val result = cmd.await()
        assertEquals(TelloCommandResult.Timeout, result)
        assertEquals(2L, transport.discardedResponsesCount)
    }

    @Test fun `valid error returns Rejected`() = runTest {
        val endpoint = FakeEndpoint()
        val transport = SerializedTelloCommandTransport(endpoint)

        val cmd = async { transport.sendCommand("takeoff", 1_000) }
        endpoint.sentSignal.receive()

        endpoint.responses.send("error Not joystick")
        val result = cmd.await()
        assertTrue(result is TelloCommandResult.Rejected)
        assertEquals("error Not joystick", (result as TelloCommandResult.Rejected).response)
    }

    @Test fun `garbage does not extend timeout deadline`() = runTest {
        val endpoint = FakeEndpoint()
        var currentTime = 0L
        val clock = MonotonicClock { currentTime }
        val transport = SerializedTelloCommandTransport(endpoint, clock)

        val cmd = async { transport.sendCommand("command", timeoutMillis = 100) }
        endpoint.sentSignal.receive()

        // Simulate 5 packets of garbage arriving at 30ms intervals
        currentTime = 30L
        endpoint.responses.send("garbage1")
        runCurrent()
        currentTime = 60L
        endpoint.responses.send("garbage2")
        runCurrent()
        currentTime = 90L
        endpoint.responses.send("garbage3")
        runCurrent()
        currentTime = 110L // After original deadline (100ms)
        endpoint.responses.send("garbage4")
        runCurrent()

        val result = cmd.await()
        assertEquals(TelloCommandResult.Timeout, result)
    }

    @Test fun `RC commands are independently serialized and sent`() = runTest {
        val endpoint = FakeEndpoint()
        val transport = SerializedTelloCommandTransport(endpoint)

        transport.sendRc(RcVector(lateral = 10, forward = -20, vertical = 30, yaw = -40))
        assertEquals(listOf("rc 10 -20 30 -40"), endpoint.sent)
    }

    @Test fun `diagnostic callback throws when endpoint send succeeds and sendRc still returns successfully`() = runTest {
        val endpoint = FakeEndpoint()
        var callbackInvoked = false
        val transport = SerializedTelloCommandTransport(
            endpoint = endpoint,
            onRcTransportSend = { _, _, _, _, _ ->
                callbackInvoked = true
                throw RuntimeException("Trace queue closed")
            },
        )

        transport.sendRc(RcVector(lateral = 0, forward = 0, vertical = 0, yaw = 10))
        assertTrue(callbackInvoked)
        assertEquals(listOf("rc 0 0 0 10"), endpoint.sent)
    }

    @Test fun `diagnostic callback throws when endpoint send fails and original failure remains thrown`() = runTest {
        val expectedError = java.io.IOException("Socket send failed")
        val endpoint = object : TelloDatagramEndpoint {
            override suspend fun send(payload: String) { throw expectedError }
            override suspend fun receive(timeoutMillis: Long): String = ""
            override suspend fun close() = Unit
        }
        var callbackInvoked = false
        val transport = SerializedTelloCommandTransport(
            endpoint = endpoint,
            onRcTransportSend = { _, _, _, _, success ->
                callbackInvoked = true
                org.junit.Assert.assertFalse(success)
                throw RuntimeException("Diagnostic error")
            },
        )

        try {
            transport.sendRc(RcVector(lateral = 0, forward = 0, vertical = 0, yaw = 10))
            org.junit.Assert.fail("Expected IOException")
        } catch (actual: Throwable) {
            assertEquals(expectedError, actual)
        }
        assertTrue(callbackInvoked)
    }

    private class FakeEndpoint(private val timeoutOnEmpty: Boolean = false) : TelloDatagramEndpoint {
        val sent = mutableListOf<String>()
        val sentSignal = Channel<Unit>(Channel.UNLIMITED)
        val responses = Channel<String>(Channel.UNLIMITED)
        override suspend fun send(payload: String) { sent += payload; sentSignal.send(Unit) }
        override suspend fun receive(timeoutMillis: Long): String {
            if (timeoutOnEmpty && responses.isEmpty) {
                throw SocketTimeoutException("Timed out")
            }
            return responses.receive()
        }
        override suspend fun close() = Unit
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
