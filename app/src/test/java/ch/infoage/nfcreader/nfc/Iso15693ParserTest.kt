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

    @Test
    fun testParseAfiFromSystemInfoWithDsfidAndAfiLoaned() {
        // Response format:
        // Byte 0: 0x00 (success)
        // Byte 1: 0x03 (DSFID + AFI flags set)
        // Bytes 2..9: UID (8 bytes)
        // Byte 10: DSFID (0x01)
        // Byte 11: AFI (0x07 = Ausgeliehen)
        val response = byteArrayOf(
            0x00, 0x03,
            0xE0.toByte(), 0x04, 0x01, 0x50, 0x12, 0x34, 0x56, 0x78,
            0x01,
            0x07
        )
        val afi = Iso15693Parser.parseAfiFromSystemInfo(response)
        assertEquals("07", afi)
    }

    @Test
    fun testParseAfiFromSystemInfoWithoutDsfidSecured() {
        // Byte 0: 0x00
        // Byte 1: 0x02 (only AFI flag set)
        // Bytes 2..9: UID
        // Byte 10: AFI (0xC2 = Gesichert)
        val response = byteArrayOf(
            0x00, 0x02,
            0xE0.toByte(), 0x04, 0x01, 0x50, 0x12, 0x34, 0x56, 0x78,
            0xC2.toByte()
        )
        val afi = Iso15693Parser.parseAfiFromSystemInfo(response)
        assertEquals("C2", afi)
    }

    @Test
    fun testParseAfiFromSystemInfoWhenAfiFlagNotSet() {
        // Byte 1: 0x01 (only DSFID)
        val response = byteArrayOf(
            0x00, 0x01,
            0xE0.toByte(), 0x04, 0x01, 0x50, 0x12, 0x34, 0x56, 0x78,
            0x00
        )
        val afi = Iso15693Parser.parseAfiFromSystemInfo(response)
        assertEquals(null, afi)
    }
}
