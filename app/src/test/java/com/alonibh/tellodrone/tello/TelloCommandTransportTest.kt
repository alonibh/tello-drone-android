@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.alonibh.tellodrone.tello

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
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
        val result = SerializedTelloCommandTransport(FakeEndpoint())
            .sendCommand("command", timeoutMillis = 100)
        assertEquals(TelloCommandResult.Timeout, result)
    }

    private class FakeEndpoint : TelloDatagramEndpoint {
        val sent = mutableListOf<String>()
        val sentSignal = Channel<Unit>(Channel.UNLIMITED)
        val responses = Channel<String>(Channel.UNLIMITED)
        override suspend fun send(payload: String) { sent += payload; sentSignal.send(Unit) }
        override suspend fun receive(timeoutMillis: Long): String = responses.receive()
        override suspend fun close() = Unit
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
