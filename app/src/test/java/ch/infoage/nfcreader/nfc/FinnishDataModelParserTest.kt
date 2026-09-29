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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinnishDataModelParserTest {

    @Test
    fun testParseAndEncodeRoundTrip() {
        val original = FinnishLibraryData(
            uid = "E004015012345678",
            version = 1,
            usageType = 2,
            parts = 4,
            partNo = 1,
            itemId = "LIB-ITEM-987654",
            country = "CH",
            isil = "ISIL-42",
            isCrcValid = true,
            isTagEmpty = false
        )

        val encodedBytes = FinnishDataModelParser.encode(original)
        assertTrue(encodedBytes.size >= 36)

        val parsed = FinnishDataModelParser.parse(original.uid, encodedBytes)
        assertNotNull(parsed)
        assertEquals("E004015012345678", parsed?.uid)
        assertEquals(1, parsed?.version)
        assertEquals(2, parsed?.usageType)
        assertEquals(4, parsed?.parts)
        assertEquals(1, parsed?.partNo)
        assertEquals("LIB-ITEM-987654", parsed?.itemId)
        assertEquals("CH", parsed?.country)
        assertEquals("ISIL-42", parsed?.isil)
        assertTrue(parsed?.isCrcValid == true)
        assertFalse(parsed?.isTagEmpty == true)
    }

    @Test
    fun testCorruptedCrcFailsValidation() {
        val original = FinnishLibraryData(
            uid = "E004015012345678",
            version = 1,
            usageType = 1,
            parts = 1,
            partNo = 1,
            itemId = "BARCODE123",
            country = "DE",
            isil = "ISIL-99",
            isCrcValid = true,
            isTagEmpty = false
        )

        val encodedBytes = FinnishDataModelParser.encode(original)
        // Corrupt an item ID byte
        encodedBytes[5] = (encodedBytes[5].toInt() xor 0xFF).toByte()

        val parsed = FinnishDataModelParser.parse(original.uid, encodedBytes)
        assertNotNull(parsed)
        assertFalse(parsed?.isCrcValid == true)
    }

    @Test
    fun testEmptyTagDetection() {
        val emptyBytes = ByteArray(36) { 0 }
        val parsed = FinnishDataModelParser.parse("E004010000000000", emptyBytes)
        assertNotNull(parsed)
        assertTrue(parsed?.isTagEmpty == true)
        assertEquals("Tag ist leer (nur Nullen)", parsed?.toFormattedString())
    }

    @Test
    fun testEmptyBytesReturnsNull() {
        val parsed = FinnishDataModelParser.parse("UID", ByteArray(0))
        assertNull(parsed)
    }

    @Test
    fun testFormattedStringOutput() {
        val data = FinnishLibraryData(
            uid = "E0040150AABBCCDD",
            version = 1,
            usageType = 1,
            parts = 2,
            partNo = 1,
            itemId = "301100001234",
            country = "DE",
            isil = "123",
            isCrcValid = true,
            isTagEmpty = false
        )

        val formatted = data.toFormattedString()
        assertEquals(
            "Item-ID: 301100001234 | ISIL: DE-123 | Teil: 1/2 | Typ: 1 | Ver: 1 | CRC: OK",
            formatted
        )
    }
}
