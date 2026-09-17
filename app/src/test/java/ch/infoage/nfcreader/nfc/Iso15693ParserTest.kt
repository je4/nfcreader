package ch.infoage.nfcreader.nfc

import org.junit.Assert.assertEquals
import org.junit.Test

class Iso15693ParserTest {

    @Test
    fun testBytesToHex() {
        val bytes = byteArrayOf(0xE0.toByte(), 0x04, 0x01, 0x50, 0x12, 0x34, 0xAB.toByte(), 0xCD.toByte())
        val hex = Iso15693Parser.bytesToHex(bytes)
        assertEquals("E00401501234ABCD", hex)
    }

    @Test
    fun testEmptyBytesToHex() {
        val bytes = byteArrayOf()
        val hex = Iso15693Parser.bytesToHex(bytes)
        assertEquals("", hex)
    }
}
