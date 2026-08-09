package com.alonibh.tellodrone.tello

import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

interface TelloDatagramEndpoint {
    suspend fun send(payload: String)
    suspend fun receive(timeoutMillis: Long): String
    suspend fun close()
}

interface TelloTransport {
    val telemetry: Flow<TelloTelemetry>
    suspend fun sendCommand(command: String, timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MILLIS): TelloCommandResult
    suspend fun sendRc(vector: RcVector)
    suspend fun close()

    companion object { const val DEFAULT_COMMAND_TIMEOUT_MILLIS = 10_000L }
}

/** One acknowledgement-bearing Tello SDK command may be in flight at a time. */
class SerializedTelloCommandTransport(
    private val endpoint: TelloDatagramEndpoint,
) {
    private val blockingCommandMutex = Mutex()
    private val sendMutex = Mutex()

    suspend fun sendCommand(command: String, timeoutMillis: Long): TelloCommandResult =
        blockingCommandMutex.withLock {
            try {
                sendMutex.withLock { endpoint.send(command) }
                val response = withTimeout(timeoutMillis + CANCELLATION_MARGIN_MILLIS) {
                    endpoint.receive(timeoutMillis)
                }.trim()
                if (response.equals("ok", ignoreCase = true)) TelloCommandResult.Success(response)
                else TelloCommandResult.Rejected(response)
            } catch (_: TimeoutCancellationException) {
                TelloCommandResult.Timeout
            } catch (_: SocketTimeoutException) {
                TelloCommandResult.Timeout
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                TelloCommandResult.Failure(error)
            }
        }

    suspend fun sendRc(vector: RcVector) {
        sendMutex.withLock { endpoint.send(vector.asCommand()) }
    }

    suspend fun close() = endpoint.close()

    companion object { private const val CANCELLATION_MARGIN_MILLIS = 250L }
}
