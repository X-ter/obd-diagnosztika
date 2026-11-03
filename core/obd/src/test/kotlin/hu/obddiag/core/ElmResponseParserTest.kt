package hu.obddiag.core

import hu.obddiag.core.elm.ElmErrorKind
import hu.obddiag.core.elm.ElmResponseParser
import hu.obddiag.core.elm.ElmResult
import hu.obddiag.core.elm.ObdProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElmResponseParserTest {

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    @Test
    fun can11SingleFrameWithHeader() {
        val r = ElmResponseParser.parseObd("7E8 04 41 0C 1A F8 \r\r", "010C", ObdProtocol.ISO_15765_4_CAN_11_500)
        assertTrue(r is ElmResult.Data)
        val m = (r as ElmResult.Data).messages.single()
        assertEquals("7E8", m.ecu)
        assertEquals("41 0C 1A F8", hex(m.data))
    }

    @Test
    fun echoAndSearchingAreRemoved() {
        val raw = "0100\rSEARCHING...\r7E8 06 41 00 BE 3F A8 13 \r\r"
        val r = ElmResponseParser.parseObd(raw, "0100", null) as ElmResult.Data
        assertEquals("41 00 BE 3F A8 13", hex(r.messages.single().data))
    }

    @Test
    fun can11MultiFrameVinIsReassembled() {
        val raw = "7E8 10 14 49 02 01 31 44 34 \r7E8 21 47 50 30 30 52 35 35 \r7E8 22 42 31 32 33 34 35 36 \r"
        val r = ElmResponseParser.parseObd(raw, "0902", ObdProtocol.ISO_15765_4_CAN_11_500) as ElmResult.Data
        val m = r.messages.single()
        assertEquals(0x14, m.data.size)
        assertEquals("1D4GP00R55B123456", String(m.data.copyOfRange(3, 20), Charsets.US_ASCII))
    }

    @Test
    fun multipleEcusAreSeparated() {
        val raw = "7E8 06 41 00 BE 3F A8 13 \r7E9 06 41 00 98 18 80 11 \r"
        val r = ElmResponseParser.parseObd(raw, "0100", ObdProtocol.ISO_15765_4_CAN_11_500) as ElmResult.Data
        assertEquals(listOf("7E8", "7E9"), r.messages.map { it.ecu })
    }

    @Test
    fun can29Header() {
        val raw = "18 DA F1 10 03 41 0D 32 \r"
        val r = ElmResponseParser.parseObd(raw, "010D", ObdProtocol.ISO_15765_4_CAN_29_500) as ElmResult.Data
        val m = r.messages.single()
        assertEquals("18DAF110", m.ecu)
        assertEquals("41 0D 32", hex(m.data))
    }

    @Test
    fun legacyJ1850DropsHeaderAndChecksum() {
        val raw = "48 6B 10 41 0C 1A F8 9C \r"
        val r = ElmResponseParser.parseObd(raw, "010C", ObdProtocol.SAE_J1850_PWM) as ElmResult.Data
        val m = r.messages.single()
        assertEquals("10", m.ecu)
        assertEquals("41 0C 1A F8", hex(m.data))
    }

    @Test
    fun legacyAutoDetected() {
        val raw = "48 6B 10 41 0D 28 E0 \r"
        val r = ElmResponseParser.parseObd(raw, "010D", null) as ElmResult.Data
        assertEquals("41 0D 28", hex(r.messages.single().data))
    }

    @Test
    fun noData() {
        assertTrue(ElmResponseParser.parseObd("NO DATA\r\r", "0142", ObdProtocol.ISO_15765_4_CAN_11_500) is ElmResult.NoData)
    }

    @Test
    fun unableToConnect() {
        val r = ElmResponseParser.parseObd("SEARCHING...\rUNABLE TO CONNECT\r", "0100", null)
        assertEquals(ElmErrorKind.UNABLE_TO_CONNECT, (r as ElmResult.Error).kind)
    }

    @Test
    fun unknownCommand() {
        val r = ElmResponseParser.parseObd("?\r", "01ZZ", null)
        assertEquals(ElmErrorKind.UNKNOWN_COMMAND, (r as ElmResult.Error).kind)
    }

    @Test
    fun negativeResponseIsReported() {
        val r = ElmResponseParser.parseObd("7E8 03 7F 0A 11 \r", "0A", ObdProtocol.ISO_15765_4_CAN_11_500) as ElmResult.Data
        assertTrue(r.messages.isEmpty())
        assertEquals(0x11, r.negatives.single().nrc)
        assertEquals(0x0A, r.negatives.single().service)
    }

    @Test
    fun responsePendingIsIgnored() {
        val raw = "7E8 03 7F 04 78 \r7E8 01 44 \r"
        val r = ElmResponseParser.parseObd(raw, "04", ObdProtocol.ISO_15765_4_CAN_11_500) as ElmResult.Data
        assertTrue(r.negatives.isEmpty())
        assertEquals("44", hex(r.messages.single().data))
    }

    @Test
    fun headerlessIndexedMultiFrame() {
        val raw = "014\r0: 49 02 01 31 44 34\r1: 47 50 30 30 52 35 35\r2: 42 31 32 33 34 35 36\r"
        val r = ElmResponseParser.parseObd(raw, "0902", null, headersEnabled = false) as ElmResult.Data
        val m = r.messages.single()
        assertEquals(20, m.data.size)
        assertEquals(0x49, m.data[0].toInt())
    }

    @Test
    fun garbageIsMalformed() {
        val r = ElmResponseParser.parseObd("7E8 0G ZZ\r", "010C", ObdProtocol.ISO_15765_4_CAN_11_500)
        assertTrue(r is ElmResult.Error)
    }

    @Test
    fun noSpacesCan11() {
        val r = ElmResponseParser.parseObd("7E804410C1AF8\r", "010C", ObdProtocol.ISO_15765_4_CAN_11_500) as ElmResult.Data
        assertEquals("41 0C 1A F8", hex(r.messages.single().data))
    }

    @Test
    fun protocolFromDpn() {
        assertEquals(ObdProtocol.ISO_15765_4_CAN_11_500, ObdProtocol.fromDpn("A6"))
        assertEquals(ObdProtocol.ISO_9141_2, ObdProtocol.fromDpn("3"))
        assertEquals(ObdProtocol.SAE_J1939_CAN, ObdProtocol.fromDpn("A"))
    }
}
