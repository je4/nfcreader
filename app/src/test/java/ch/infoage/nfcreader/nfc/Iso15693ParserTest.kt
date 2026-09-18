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
}
