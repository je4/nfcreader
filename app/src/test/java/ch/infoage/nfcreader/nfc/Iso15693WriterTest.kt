/*
 * Copyright 2026 info-age GmbH, Basel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ch.infoage.nfcreader.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Iso15693WriterTest {

    @Test
    fun testFormatHexDumpNullOrEmpty() {
        assertEquals("Keine Rohdaten verfügbar.", Iso15693Writer.formatHexDump(null))
        assertEquals("Keine Rohdaten verfügbar.", Iso15693Writer.formatHexDump(ByteArray(0)))
    }

    @Test
    fun testFormatHexDumpWithBlocks() {
        val testData = byteArrayOf(
            0x11, 0x01, 0x01, 0x33, // Block 0: ...3
            0x30, 0x31, 0x31, 0x38  // Block 1: 0118
        )
        val dump = Iso15693Writer.formatHexDump(testData, blockSize = 4)
        val lines = dump.split("\n")
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("Block 00:"))
        assertTrue(lines[0].contains("11 01 01 33"))
        assertTrue(lines[0].contains("...3"))
        assertTrue(lines[1].contains("Block 01:"))
        assertTrue(lines[1].contains("30 31 31 38"))
        assertTrue(lines[1].contains("0118"))
    }

    @Test
    fun testFormatHexDumpWithFullFinnishDataModel() {
        val original = FinnishLibraryData(
            uid = "E004015012345678",
            version = 1,
            usageType = 1,
            parts = 1,
            partNo = 1,
            itemId = "301184",
            country = "CH",
            isil = "ISIL-123",
            isCrcValid = true,
            isTagEmpty = false,
            afi = "C2"
        )
        val encoded = FinnishDataModelParser.encode(original, size = 36)
        val dump = Iso15693Writer.formatHexDump(encoded, blockSize = 4)
        val lines = dump.split("\n")
        assertEquals(9, lines.size) // 36 / 4 = 9 blocks
        assertTrue(lines[0].startsWith("Block 00:"))
        assertTrue(lines[8].startsWith("Block 08:"))
    }

    @Test
    fun testXorPasswordCalculationForIcodeSlix() {
        // Given random number response: RN[0]=0x7C, RN[1]=0x06
        val rn0 = 0x7C.toByte()
        val rn1 = 0x06.toByte()

        // Test with default password 00000000
        val defaultPwdLsb = ch.infoage.nfcreader.data.local.AppSettings.hex32ToBytes("00000000", lsbFirst = true)
        val xorDefault = byteArrayOf(
            (defaultPwdLsb[0].toInt() xor rn0.toInt()).toByte(),
            (defaultPwdLsb[1].toInt() xor rn1.toInt()).toByte(),
            (defaultPwdLsb[2].toInt() xor rn0.toInt()).toByte(),
            (defaultPwdLsb[3].toInt() xor rn1.toInt()).toByte()
        )
        assertEquals(0x7C.toByte(), xorDefault[0])
        assertEquals(0x06.toByte(), xorDefault[1])
        assertEquals(0x7C.toByte(), xorDefault[2])
        assertEquals(0x06.toByte(), xorDefault[3])

        // Test with known password 0F0F0F0F -> Expected XOR 0x73097309 (LSB first: 73 09 73 09)
        val pwd0fLsb = ch.infoage.nfcreader.data.local.AppSettings.hex32ToBytes("0F0F0F0F", lsbFirst = true)
        val xor0f = byteArrayOf(
            (pwd0fLsb[0].toInt() xor rn0.toInt()).toByte(),
            (pwd0fLsb[1].toInt() xor rn1.toInt()).toByte(),
            (pwd0fLsb[2].toInt() xor rn0.toInt()).toByte(),
            (pwd0fLsb[3].toInt() xor rn1.toInt()).toByte()
        )
        assertEquals(0x73.toByte(), xor0f[0])
        assertEquals(0x09.toByte(), xor0f[1])
        assertEquals(0x73.toByte(), xor0f[2])
        assertEquals(0x09.toByte(), xor0f[3])
    }
}
