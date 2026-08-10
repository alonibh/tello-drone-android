package com.alonibh.tellodrone.tello

import android.net.Network
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NetworkDatagramEndpoint(
    network: Network,
    localPort: Int,
    remoteAddress: InetSocketAddress? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TelloDatagramEndpoint {
    private val socket = DatagramSocket(null).apply {
        reuseAddress = true
        network.bindSocket(this)
        bind(InetSocketAddress(localPort))
        if (remoteAddress != null) connect(remoteAddress)
    }
    private val remote = remoteAddress

    override suspend fun send(payload: String) = withContext(dispatcher) {
        val bytes = payload.toByteArray(StandardCharsets.US_ASCII)
        val packet = if (socket.isConnected) DatagramPacket(bytes, bytes.size)
        else DatagramPacket(bytes, bytes.size, requireNotNull(remote))
        socket.send(packet)
    }

    override suspend fun receive(timeoutMillis: Long): String = withContext(dispatcher) {
        socket.soTimeout = timeoutMillis.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        val buffer = ByteArray(MAX_PACKET_BYTES)
        val packet = DatagramPacket(buffer, buffer.size)
        socket.receive(packet)
        String(packet.data, packet.offset, packet.length, StandardCharsets.US_ASCII)
    }

    override suspend fun close() = withContext(dispatcher) { socket.close() }

    companion object { const val MAX_PACKET_BYTES = 2_048 }
}

class NetworkTelloTransport(
    network: Network,
    scope: CoroutineScope,
    private val clock: MonotonicClock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TelloTransport {
    private val commandEndpoint = NetworkDatagramEndpoint(
        network = network,
        localPort = CONTROL_LOCAL_PORT,
        remoteAddress = InetSocketAddress(InetAddress.getByName(TELLO_IP), COMMAND_PORT),
        dispatcher = dispatcher,
    )
    private val telemetryEndpoint = NetworkDatagramEndpoint(
        network = network,
        localPort = STATE_PORT,
        dispatcher = dispatcher,
    )
    private val commands = SerializedTelloCommandTransport(commandEndpoint)
    private val mutableTelemetry = MutableSharedFlow<TelloTelemetry>(replay = 1, extraBufferCapacity = 8)
    override val telemetry: Flow<TelloTelemetry> = mutableTelemetry.asSharedFlow()
    private val telemetryJob: Job = scope.launch(dispatcher) {
        while (isActive) {
            try {
                TelloTelemetryParser.parse(
                    packet = telemetryEndpoint.receive(timeoutMillis = 0),
                    receivedAtMonotonicMillis = clock.nowMillis(),
                )?.let { mutableTelemetry.emit(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (isActive) break
            }
        }
    }

    override suspend fun sendCommand(command: String, timeoutMillis: Long): TelloCommandResult =
        commands.sendCommand(command, timeoutMillis)

    override suspend fun sendRc(vector: RcVector) = commands.sendRc(vector)

    override suspend fun close() {
        telemetryJob.cancel()
        telemetryEndpoint.close()
        commands.close()
    }

    companion object {
        const val TELLO_IP = "192.168.10.1"
        const val COMMAND_PORT = 8_889
        /** Tello SDK commands and acknowledgements share UDP port 8889. */
        const val CONTROL_LOCAL_PORT = COMMAND_PORT
        const val STATE_PORT = 8_890
    }
}
