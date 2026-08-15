package com.alonibh.tellodrone.tello

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelloH264FramingTest {
    @Test fun `parses three and four byte Annex-B start codes`() {
        val nals = AnnexBParser.parse(bytes(0, 0, 1, 0x67, 1, 2, 0, 0, 0, 1, 0x68, 3))

        assertEquals(listOf(H264NalUnitType.SPS, H264NalUnitType.PPS), nals.map { it.type })
        assertArrayEquals(bytes(0, 0, 0, 1, 0x67, 1, 2), nals[0].bytes)
        assertArrayEquals(bytes(0, 0, 0, 1, 0x68, 3), nals[1].bytes)
    }

    @Test fun `preserves start code split across datagrams`() {
        val assembler = TelloH264AccessUnitAssembler(maxAccessUnitBytes = 64, fullDatagramBytes = 5)

        assertNull(assembler.offerDatagram(bytes(0, 0, 0, 1, 0x67)))
        assertNull(assembler.offerDatagram(bytes(9, 0, 0, 0, 1)))
        val unit = assembler.offerDatagram(bytes(0x65, 7))

        assertNotNull(unit)
        assertEquals(setOf(H264NalUnitType.SPS, H264NalUnitType.IDR), unit!!.nalUnitTypes)
        assertArrayEquals(bytes(0, 0, 0, 1, 0x67, 9, 0, 0, 0, 1, 0x65, 7), unit.bytes)
    }

    @Test fun `emits multiple NAL units from one datagram and preserves decoder bootstrap units`() {
        val assembler = TelloH264AccessUnitAssembler(maxAccessUnitBytes = 128)
        val unit = assembler.offerDatagram(
            bytes(0, 0, 0, 1, 0x67, 1, 0, 0, 1, 0x68, 2, 0, 0, 0, 1, 0x65, 3),
        )!!

        assertTrue(unit.hasSequenceParameterSet)
        assertTrue(unit.hasPictureParameterSet)
        assertTrue(unit.hasIdr)
        assertEquals(
            listOf(H264NalUnitType.SPS, H264NalUnitType.PPS, H264NalUnitType.IDR),
            AnnexBParser.parse(unit.bytes).map { it.type },
        )
    }

    @Test fun `oversized units are bounded and dropped until a clean packet boundary`() {
        val assembler = TelloH264AccessUnitAssembler(maxAccessUnitBytes = 8, fullDatagramBytes = 5)

        assertNull(assembler.offerDatagram(bytes(0, 0, 0, 1, 0x65)))
        assertNull(assembler.offerDatagram(bytes(1, 2, 3, 4, 5)))
        assertNull(assembler.offerDatagram(bytes(6)))
        assertEquals(1, assembler.droppedAccessUnits)

        val recovered = assembler.offerDatagram(bytes(0, 0, 1, 0x65))
        assertNotNull(recovered)
        assertTrue(recovered!!.hasIdr)
    }

    @Test fun `bounded buffer delivers ordinary access units in decode order without skips`() {
        val buffer = BoundedAccessUnitBuffer(capacity = 4)
        val pictures = (1..4).map { picture(it) }

        pictures.forEach(buffer::offer)

        pictures.forEach { expected -> assertArrayEquals(expected.bytes, buffer.pollUnit()!!.bytes) }
        assertNull(buffer.poll())
        assertEquals(0, buffer.droppedAccessUnits)
    }

    @Test fun `overflow declares discontinuity and never sends later P frames to old decoder`() {
        val buffer = BoundedAccessUnitBuffer(capacity = 3)
        (1..3).forEach { buffer.offer(picture(it)) }

        buffer.offer(picture(4))
        buffer.offer(picture(5))

        assertTrue(buffer.poll() is H264DecodeInput.Discontinuity)
        assertNull(buffer.poll())
        assertTrue(buffer.isWaitingForIdr())
        assertEquals(1, buffer.discontinuities)
        assertEquals(5, buffer.droppedAccessUnits)
    }

    @Test fun `decoder handoff waits for IDR after discontinuity and then recovers`() {
        val buffer = BoundedAccessUnitBuffer(capacity = 3)
        buffer.declareDiscontinuity()
        buffer.offer(picture(1))
        buffer.offer(picture(2))

        assertTrue(buffer.poll() is H264DecodeInput.Discontinuity)
        assertNull(buffer.poll())

        val idr = H264AccessUnit(bytes(0, 0, 0, 1, 0x65, 9), setOf(H264NalUnitType.IDR))
        buffer.offer(idr)

        assertArrayEquals(idr.bytes, buffer.pollUnit()!!.bytes)
        assertFalse(buffer.isWaitingForIdr())
        assertNull(buffer.poll())
    }

    @Test fun `SPS and PPS remain available to bootstrap recovery IDR`() {
        val buffer = BoundedAccessUnitBuffer(capacity = 3)
        val sps = H264AccessUnit(bytes(0, 0, 0, 1, 0x67, 7), setOf(H264NalUnitType.SPS))
        val pps = H264AccessUnit(bytes(0, 0, 0, 1, 0x68, 8), setOf(H264NalUnitType.PPS))
        val idr = H264AccessUnit(bytes(0, 0, 0, 1, 0x65, 5), setOf(H264NalUnitType.IDR))
        buffer.offer(sps)
        buffer.offer(pps)
        buffer.offer(picture(1))

        buffer.offer(picture(2))
        assertTrue(buffer.poll() is H264DecodeInput.Discontinuity)
        buffer.offer(idr)

        assertArrayEquals(sps.bytes, buffer.pollUnit()!!.bytes)
        assertArrayEquals(pps.bytes, buffer.pollUnit()!!.bytes)
        assertArrayEquals(idr.bytes, buffer.pollUnit()!!.bytes)
        assertNull(buffer.poll())
    }

    @Test fun `recovery retains only parameter sets from a combined bootstrap access unit`() {
        val buffer = BoundedAccessUnitBuffer(capacity = 3)
        val combined = H264AccessUnit(
            bytes(
                0, 0, 0, 1, 0x67, 7,
                0, 0, 0, 1, 0x68, 8,
                0, 0, 0, 1, 0x65, 1,
            ),
            setOf(H264NalUnitType.SPS, H264NalUnitType.PPS, H264NalUnitType.IDR),
        )
        val recoveryIdr = H264AccessUnit(bytes(0, 0, 0, 1, 0x65, 2), setOf(H264NalUnitType.IDR))
        buffer.offer(combined)
        buffer.offer(picture(1))
        buffer.offer(picture(2))

        buffer.offer(picture(3))
        assertTrue(buffer.poll() is H264DecodeInput.Discontinuity)
        buffer.offer(recoveryIdr)

        assertEquals(setOf(H264NalUnitType.SPS), buffer.pollUnit()!!.nalUnitTypes)
        assertEquals(setOf(H264NalUnitType.PPS), buffer.pollUnit()!!.nalUnitTypes)
        assertArrayEquals(recoveryIdr.bytes, buffer.pollUnit()!!.bytes)
        assertNull(buffer.poll())
    }

    @Test fun `access unit buffering remains bounded under sustained decoder backpressure`() {
        val capacity = 4
        val buffer = BoundedAccessUnitBuffer(capacity)
        var maximumPending = 0

        repeat(1_000) { index ->
            val unit = if (index % 30 == 0) {
                H264AccessUnit(bytes(index), setOf(H264NalUnitType.IDR))
            } else {
                picture(index)
            }
            buffer.offer(unit)
            maximumPending = maxOf(maximumPending, buffer.pendingAccessUnits())
        }

        assertTrue(maximumPending <= capacity)
        assertTrue(buffer.pendingAccessUnits() <= capacity)
        assertTrue(buffer.discontinuities > 0)

        buffer.reset()
        assertNull(buffer.poll())
        assertEquals(0, buffer.droppedAccessUnits)
        assertEquals(0, buffer.discontinuities)
    }

    @Test fun `assembler reset discards a partial unit before restart`() {
        val assembler = TelloH264AccessUnitAssembler(maxAccessUnitBytes = 64, fullDatagramBytes = 5)
        assertNull(assembler.offerDatagram(bytes(0, 0, 0, 1, 0x67)))
        assembler.reset()

        val unit = assembler.offerDatagram(bytes(0, 0, 1, 0x65))!!
        assertFalse(unit.hasSequenceParameterSet)
        assertTrue(unit.hasIdr)
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun picture(id: Int) = H264AccessUnit(bytes(id), setOf(1))

    private fun BoundedAccessUnitBuffer.pollUnit(): H264AccessUnit? =
        (poll() as? H264DecodeInput.AccessUnit)?.value
}
