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

sealed interface H264DecodeInput {
    data object Discontinuity : H264DecodeInput
    data class AccessUnit(val value: H264AccessUnit) : H264DecodeInput
}

/**
 * Non-blocking, bounded receiver-to-decoder handoff. Complete access units are delivered FIFO so
 * inter-frame references are never silently skipped. If capacity is exhausted, queued pictures are
 * discarded as one explicit discontinuity and ordinary pictures are suppressed until an IDR can
 * restart the decoder. The latest SPS/PPS are retained separately to bootstrap that IDR.
 */
class BoundedAccessUnitBuffer(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity >= MIN_RECOVERY_CAPACITY)
    }

    private val lock = Any()
    private val queued = ArrayDeque<H264AccessUnit>(capacity)
    private var retainedSps: H264AccessUnit? = null
    private var retainedPps: H264AccessUnit? = null
    private var discontinuityPending = false
    private var waitingForIdr = false
    var droppedAccessUnits: Long = 0
        private set
    var discontinuities: Long = 0
        private set

    fun offer(value: H264AccessUnit) = synchronized(lock) {
        retainParameterSets(value)
        if (waitingForIdr) {
            if (value.hasIdr) enqueueRecovery(value) else if (!value.isParameterSetOnly) droppedAccessUnits++
            return@synchronized
        }

        if (queued.size == capacity) {
            declareDiscontinuityLocked()
            if (value.hasIdr) enqueueRecovery(value) else if (!value.isParameterSetOnly) droppedAccessUnits++
            return@synchronized
        }
        queued.addLast(value)
    }

    fun declareDiscontinuity() = synchronized(lock) {
        declareDiscontinuityLocked()
    }

    fun poll(): H264DecodeInput? = synchronized(lock) {
        if (discontinuityPending) {
            discontinuityPending = false
            H264DecodeInput.Discontinuity
        } else {
            queued.removeFirstOrNull()?.let(H264DecodeInput::AccessUnit)
        }
    }

    fun pendingAccessUnits(): Int = synchronized(lock) { queued.size }

    fun isWaitingForIdr(): Boolean = synchronized(lock) { waitingForIdr }

    private fun retainParameterSets(value: H264AccessUnit) {
        if (!value.hasSequenceParameterSet && !value.hasPictureParameterSet) return
        AnnexBParser.parse(value.bytes).forEach { nal ->
            when (nal.type) {
                H264NalUnitType.SPS -> retainedSps = H264AccessUnit(nal.bytes, setOf(H264NalUnitType.SPS))
                H264NalUnitType.PPS -> retainedPps = H264AccessUnit(nal.bytes, setOf(H264NalUnitType.PPS))
            }
        }
    }

    private fun declareDiscontinuityLocked() {
        droppedAccessUnits += queued.size
        queued.clear()
        discontinuityPending = true
        waitingForIdr = true
        discontinuities++
    }

    private fun enqueueRecovery(idr: H264AccessUnit) {
        if (!idr.hasSequenceParameterSet) retainedSps?.let(queued::addLast)
        if (!idr.hasPictureParameterSet) {
            retainedPps?.takeUnless { it === retainedSps }?.let(queued::addLast)
        }
        queued.addLast(idr)
        waitingForIdr = false
    }

    fun reset() = synchronized(lock) {
        queued.clear()
        retainedSps = null
        retainedPps = null
        discontinuityPending = false
        waitingForIdr = false
        droppedAccessUnits = 0
        discontinuities = 0
    }

    private val H264AccessUnit.isParameterSetOnly: Boolean
        get() = nalUnitTypes.isNotEmpty() && nalUnitTypes.all {
            it == H264NalUnitType.SPS || it == H264NalUnitType.PPS
        }

    companion object {
        const val DEFAULT_CAPACITY = 8
        private const val MIN_RECOVERY_CAPACITY = 3
    }
}
