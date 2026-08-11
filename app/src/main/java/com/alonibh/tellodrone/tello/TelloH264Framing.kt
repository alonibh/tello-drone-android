package com.alonibh.tellodrone.tello

/** A complete decoder access unit in Annex-B form, including its original start codes. */
data class H264AccessUnit(
    val bytes: ByteArray,
    val nalUnitTypes: Set<Int>,
) {
    val hasSequenceParameterSet: Boolean get() = H264NalUnitType.SPS in nalUnitTypes
    val hasPictureParameterSet: Boolean get() = H264NalUnitType.PPS in nalUnitTypes
    val hasIdr: Boolean get() = H264NalUnitType.IDR in nalUnitTypes
}

object H264NalUnitType {
    const val IDR = 5
    const val SPS = 7
    const val PPS = 8
}

data class AnnexBNalUnit(
    val type: Int,
    val bytes: ByteArray,
)

/**
 * Stateless Annex-B scanner supporting both three- and four-byte start codes. The access-unit
 * assembler retains split datagrams first, so a start code split across UDP packets arrives here
 * intact. Leading transport noise is discarded and every emitted NAL is normalized to a four-byte
 * start code, which MediaCodec accepts as AVC byte-stream input.
 */
object AnnexBParser {
    fun parse(bytes: ByteArray, length: Int = bytes.size): List<AnnexBNalUnit> {
        val limit = length.coerceIn(0, bytes.size)
        val starts = mutableListOf<StartCode>()
        var index = 0
        while (index < limit - 2) {
            val startLength = startCodeLengthAt(bytes, index, limit)
            if (startLength != 0) {
                starts += StartCode(index, startLength)
                index += startLength
            } else {
                index++
            }
        }
        if (starts.isEmpty()) return emptyList()

        return starts.mapIndexedNotNull { position, start ->
            val payloadStart = start.offset + start.length
            val payloadEnd = if (position + 1 < starts.size) starts[position + 1].offset else limit
            if (payloadStart >= payloadEnd) null else {
                val normalized = ByteArray(START_CODE.size + payloadEnd - payloadStart)
                START_CODE.copyInto(normalized)
                bytes.copyInto(normalized, START_CODE.size, payloadStart, payloadEnd)
                AnnexBNalUnit(bytes[payloadStart].toInt() and 0x1f, normalized)
            }
        }
    }

    private fun startCodeLengthAt(bytes: ByteArray, offset: Int, limit: Int): Int = when {
        offset + 3 < limit && bytes[offset] == ZERO && bytes[offset + 1] == ZERO &&
            bytes[offset + 2] == ZERO && bytes[offset + 3] == ONE -> 4
        offset + 2 < limit && bytes[offset] == ZERO && bytes[offset + 1] == ZERO &&
            bytes[offset + 2] == ONE -> 3
        else -> 0
    }

    private data class StartCode(val offset: Int, val length: Int)
    private val START_CODE = byteArrayOf(0, 0, 0, 1)
    private const val ZERO: Byte = 0
    private const val ONE: Byte = 1
}

/**
 * Isolates the only Tello packet-boundary heuristic: video datagrams are normally 1460 bytes and
 * a shorter datagram terminates an encoded access unit. UDP packets are accumulated before Annex-B
 * scanning, so neither NAL units nor start codes need to align with datagram boundaries.
 *
 * The fixed-size buffer bounds memory. Overflow drops the incomplete unit and ignores its tail
 * until the next Tello boundary, favoring decoder recovery at a later SPS/PPS/IDR over latency.
 */
class TelloH264AccessUnitAssembler(
    private val maxAccessUnitBytes: Int = DEFAULT_MAX_ACCESS_UNIT_BYTES,
    private val fullDatagramBytes: Int = TELLO_FULL_DATAGRAM_BYTES,
) {
    init {
        require(maxAccessUnitBytes > 0)
        require(fullDatagramBytes > 0)
    }

    private val buffer = ByteArray(maxAccessUnitBytes)
    private var size = 0
    private var droppingUntilBoundary = false
    var droppedAccessUnits: Long = 0
        private set

    fun offerDatagram(bytes: ByteArray, length: Int = bytes.size): H264AccessUnit? {
        require(length in 0..bytes.size)
        val isBoundary = length < fullDatagramBytes
        if (!droppingUntilBoundary) {
            if (length > buffer.size - size) {
                size = 0
                droppingUntilBoundary = true
                droppedAccessUnits++
            } else {
                bytes.copyInto(buffer, size, 0, length)
                size += length
            }
        }

        if (!isBoundary) return null
        if (droppingUntilBoundary) {
            droppingUntilBoundary = false
            size = 0
            return null
        }

        val nals = AnnexBParser.parse(buffer, size)
        size = 0
        if (nals.isEmpty()) {
            droppedAccessUnits++
            return null
        }
        val outputSize = nals.sumOf { it.bytes.size }
        val output = ByteArray(outputSize)
        var outputOffset = 0
        nals.forEach { nal ->
            nal.bytes.copyInto(output, outputOffset)
            outputOffset += nal.bytes.size
        }
        return H264AccessUnit(output, nals.mapTo(mutableSetOf()) { it.type })
    }

    fun reset() {
        size = 0
        droppingUntilBoundary = false
    }

    companion object {
        const val TELLO_FULL_DATAGRAM_BYTES = 1_460
        const val DEFAULT_MAX_ACCESS_UNIT_BYTES = 512 * 1_024
    }
}

/**
 * Bounded handoff: ordinary pictures use a single latest slot while up to three decoder-recovery
 * units (SPS/PPS/IDR) are retained ahead of it. This avoids losing bootstrap data to a newer P
 * frame without allowing an unbounded latency queue.
 */
class LatestAccessUnitBuffer {
    private val lock = Any()
    private val recovery = ArrayDeque<H264AccessUnit>(MAX_RECOVERY_UNITS)
    private var latest: H264AccessUnit? = null
    var droppedAccessUnits: Long = 0
        private set

    fun offer(value: H264AccessUnit) = synchronized(lock) {
        if (value.hasSequenceParameterSet || value.hasPictureParameterSet || value.hasIdr) {
            val removed = when {
                value.hasSequenceParameterSet -> recovery.size.also { recovery.clear() }
                else -> {
                    val previousSize = recovery.size
                    recovery.removeAll { existing ->
                        (value.hasPictureParameterSet && existing.hasPictureParameterSet) ||
                            (value.hasIdr && existing.hasIdr)
                    }
                    previousSize - recovery.size
                }
            }
            droppedAccessUnits += removed
            if (recovery.size == MAX_RECOVERY_UNITS) {
                recovery.removeFirst()
                droppedAccessUnits++
            }
            recovery.addLast(value)
        } else {
            if (latest != null) droppedAccessUnits++
            latest = value
        }
    }

    fun pollLatest(): H264AccessUnit? = synchronized(lock) {
        if (recovery.isNotEmpty()) recovery.removeFirst() else latest.also { latest = null }
    }

    fun reset() = synchronized(lock) {
        recovery.clear()
        latest = null
        droppedAccessUnits = 0
    }

    companion object { private const val MAX_RECOVERY_UNITS = 3 }
}
