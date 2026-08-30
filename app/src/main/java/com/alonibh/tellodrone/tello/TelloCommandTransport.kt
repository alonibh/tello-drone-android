package com.alonibh.tellodrone.tello

import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
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
    private val clock: MonotonicClock = MonotonicClock { System.nanoTime() / 1_000_000L },
    private val onDiscardedResponse: ((String) -> Unit)? = null,
    private val onRcTransportSend: ((sequence: Long, payload: String, startedAtNanos: Long, completedAtNanos: Long, success: Boolean) -> Unit)? = null,
) {
    private val blockingCommandMutex = Mutex()
    private val sendMutex = Mutex()
    private val discardedCount = AtomicLong(0L)
    private val transportRcSequence = AtomicLong(0L)

    val discardedResponsesCount: Long get() = discardedCount.get()

    suspend fun sendCommand(command: String, timeoutMillis: Long): TelloCommandResult =
        blockingCommandMutex.withLock {
            try {
                sendMutex.withLock { endpoint.send(command) }
                val startMillis = clock.nowMillis()
                val deadlineMillis = startMillis + timeoutMillis
                var result: TelloCommandResult? = null

                while (result == null) {
                    val nowMillis = clock.nowMillis()
                    val remainingMillis = deadlineMillis - nowMillis
                    if (remainingMillis <= 0L) {
                        result = TelloCommandResult.Timeout
                        break
                    }
                    val rawResponse = try {
                        withTimeout(remainingMillis + CANCELLATION_MARGIN_MILLIS) {
                            endpoint.receive(remainingMillis)
                        }
                    } catch (_: TimeoutCancellationException) {
                        result = TelloCommandResult.Timeout
                        break
                    } catch (_: SocketTimeoutException) {
                        result = TelloCommandResult.Timeout
                        break
                    }

                    when (val classification = classifyResponse(rawResponse)) {
                        is ResponseClassification.Accept -> {
                            result = TelloCommandResult.Success(classification.text)
                        }
                        is ResponseClassification.Reject -> {
                            result = TelloCommandResult.Rejected(classification.text)
                        }
                        is ResponseClassification.Discard -> {
                            discardedCount.incrementAndGet()
                            onDiscardedResponse?.invoke(classification.reason)
                        }
                    }
                }
                result ?: TelloCommandResult.Timeout
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                TelloCommandResult.Failure(error)
            }
        }

    suspend fun sendRc(vector: RcVector) {
        val seq = transportRcSequence.incrementAndGet()
        val payload = vector.asCommand()
        val startNanos = System.nanoTime()
        var success = false
        try {
            sendMutex.withLock { endpoint.send(payload) }
            success = true
        } finally {
            val endNanos = System.nanoTime()
            onRcTransportSend?.invoke(seq, payload, startNanos, endNanos, success)
        }
    }

    suspend fun close() = endpoint.close()

    companion object {
        private const val CANCELLATION_MARGIN_MILLIS = 250L

        internal sealed interface ResponseClassification {
            data class Accept(val text: String) : ResponseClassification
            data class Reject(val text: String) : ResponseClassification
            data class Discard(val reason: String) : ResponseClassification
        }

        internal fun classifyResponse(raw: String): ResponseClassification {
            val isPrintable = raw.all { it in '\u0020'..'\u007E' || it == '\r' || it == '\n' || it == '\t' }
            if (!isPrintable) {
                return ResponseClassification.Discard("binary or non-printable payload")
            }
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                return ResponseClassification.Discard("empty payload")
            }
            if (trimmed.equals("ok", ignoreCase = true)) {
                return ResponseClassification.Accept(trimmed)
            }
            if (trimmed.startsWith("error", ignoreCase = true)) {
                return ResponseClassification.Reject(trimmed)
            }
            if (trimmed.contains(';') && (trimmed.contains("bat:") || trimmed.contains("pitch:") || trimmed.contains("h:"))) {
                return ResponseClassification.Discard("unrelated telemetry packet on command socket")
            }
            return ResponseClassification.Discard("unrecognized command response '$trimmed'")
        }
    }
}
// SPDX-License-Identifier: AGPL-3.0-only
