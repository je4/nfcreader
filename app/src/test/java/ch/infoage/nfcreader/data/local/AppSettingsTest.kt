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

package ch.infoage.nfcreader.data.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {

    @Test
    fun testDefaultPasswordsAreZero() {
        assertEquals("00000000", AppSettings.DEFAULT_AFI_PASSWORD)
        assertEquals("00000000", AppSettings.DEFAULT_WRITE_PASSWORD)
    }

    @Test
    fun testDefaultSelectedTabIsScan() {
        assertEquals("SCAN", AppSettings.DEFAULT_SELECTED_TAB)
    }

    @Test
    fun testNormalizeHex32() {
        assertEquals("00000000", AppSettings.normalizeHex32(""))
        assertEquals("00000000", AppSettings.normalizeHex32("   "))
        assertEquals("00000000", AppSettings.normalizeHex32("0"))
        assertEquals("00000000", AppSettings.normalizeHex32("00000000"))
        assertEquals("00001234", AppSettings.normalizeHex32("1234"))
        assertEquals("A1B2C3D4", AppSettings.normalizeHex32("a1b2c3d4"))
        assertEquals("A1B2C3D4", AppSettings.normalizeHex32("0xa1b2c3d4"))
        assertEquals("A1B2C3D4", AppSettings.normalizeHex32("0XA1B2C3D4"))
        assertEquals("A1B2C3D4", AppSettings.normalizeHex32("A1 B2 C3 D4"))
        assertEquals("A1B2C3D4", AppSettings.normalizeHex32("A1:B2:C3:D4"))
    }

    @Test
    fun testIsValidHex32() {
        // Valid 32-bit hex (exactly 8 hex chars or empty which defaults to 00000000)
        assertTrue(AppSettings.isValidHex32(""))
        assertTrue(AppSettings.isValidHex32("   "))
        assertTrue(AppSettings.isValidHex32("00000000"))
        assertTrue(AppSettings.isValidHex32("FFFFFFFF"))
        assertTrue(AppSettings.isValidHex32("ffffffff"))
        assertTrue(AppSettings.isValidHex32("0xFFFFFFFF"))
        assertTrue(AppSettings.isValidHex32("0x00000000"))
        assertTrue(AppSettings.isValidHex32("12 34 56 78"))
        assertTrue(AppSettings.isValidHex32("12:34:56:78"))

        // Invalid: does not match 32-bit hex length (< 8 or > 8 hex digits)
        assertFalse(AppSettings.isValidHex32("0"))
        assertFalse(AppSettings.isValidHex32("0x0"))
        assertFalse(AppSettings.isValidHex32("1234"))
        assertFalse(AppSettings.isValidHex32("1234567"))
        assertFalse(AppSettings.isValidHex32("100000000"))
        assertFalse(AppSettings.isValidHex32("123456789"))
        // Invalid: non-hex characters
        assertFalse(AppSettings.isValidHex32("GHIJKLMN"))
        assertFalse(AppSettings.isValidHex32("1234567Z"))
    }

    @Test
    fun testParseHex32ToLong() {
        assertEquals(0L, AppSettings.parseHex32ToLong(""))
        assertEquals(0L, AppSettings.parseHex32ToLong("0"))
        assertEquals(0L, AppSettings.parseHex32ToLong("00000000"))
        assertEquals(0x12345678L, AppSettings.parseHex32ToLong("12345678"))
        assertEquals(0xFFFFFFFFL, AppSettings.parseHex32ToLong("FFFFFFFF"))
        assertEquals(0x0FL, AppSettings.parseHex32ToLong("0x0F"))
    }

    @Test
    fun testHex32ToBytes() {
        val zeroBytes = AppSettings.hex32ToBytes("0")
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x00, 0x00), zeroBytes)

        val fullBytes = AppSettings.hex32ToBytes("A1B2C3D4")
        assertArrayEquals(
            byteArrayOf(0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte(), 0xD4.toByte()),
            fullBytes
        )

        val lsbBytes = AppSettings.hex32ToBytes("A1B2C3D4", lsbFirst = true)
        assertArrayEquals(
            byteArrayOf(0xD4.toByte(), 0xC3.toByte(), 0xB2.toByte(), 0xA1.toByte()),
            lsbBytes
        )
    }
}
