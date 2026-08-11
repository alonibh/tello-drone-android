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

    @Test fun `latest buffer drops old access units and reset clears state`() {
        val buffer = LatestAccessUnitBuffer()
        val first = H264AccessUnit(bytes(1), setOf(1))
        val newest = H264AccessUnit(bytes(2), setOf(2))

        buffer.offer(first)
        buffer.offer(newest)
        assertEquals(1, buffer.droppedAccessUnits)
        assertArrayEquals(newest.bytes, buffer.pollLatest()!!.bytes)
        assertNull(buffer.pollLatest())

        buffer.offer(first)
        buffer.reset()
        assertNull(buffer.pollLatest())
        assertEquals(0, buffer.droppedAccessUnits)
    }

    @Test fun `latest buffer retains bounded SPS PPS and IDR recovery ahead of newest picture`() {
        val buffer = LatestAccessUnitBuffer()
        val sps = H264AccessUnit(bytes(7), setOf(H264NalUnitType.SPS))
        val pps = H264AccessUnit(bytes(8), setOf(H264NalUnitType.PPS))
        val idr = H264AccessUnit(bytes(5), setOf(H264NalUnitType.IDR))
        val newestPicture = H264AccessUnit(bytes(1), setOf(1))

        buffer.offer(sps)
        buffer.offer(pps)
        buffer.offer(idr)
        buffer.offer(newestPicture)

        assertEquals(H264NalUnitType.SPS, buffer.pollLatest()!!.nalUnitTypes.single())
        assertEquals(H264NalUnitType.PPS, buffer.pollLatest()!!.nalUnitTypes.single())
        assertEquals(H264NalUnitType.IDR, buffer.pollLatest()!!.nalUnitTypes.single())
        assertArrayEquals(newestPicture.bytes, buffer.pollLatest()!!.bytes)
        assertNull(buffer.pollLatest())
    }

    @Test fun `newer IDR replaces stale recovery picture without losing parameter sets`() {
        val buffer = LatestAccessUnitBuffer()
        val sps = H264AccessUnit(bytes(7), setOf(H264NalUnitType.SPS))
        val pps = H264AccessUnit(bytes(8), setOf(H264NalUnitType.PPS))
        val oldIdr = H264AccessUnit(bytes(5, 1), setOf(H264NalUnitType.IDR))
        val newIdr = H264AccessUnit(bytes(5, 2), setOf(H264NalUnitType.IDR))

        buffer.offer(sps)
        buffer.offer(pps)
        buffer.offer(oldIdr)
        buffer.offer(newIdr)

        assertEquals(H264NalUnitType.SPS, buffer.pollLatest()!!.nalUnitTypes.single())
        assertEquals(H264NalUnitType.PPS, buffer.pollLatest()!!.nalUnitTypes.single())
        assertArrayEquals(newIdr.bytes, buffer.pollLatest()!!.bytes)
        assertNull(buffer.pollLatest())
        assertEquals(1, buffer.droppedAccessUnits)
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
}
