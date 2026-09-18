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

import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NfcV
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

data class ParsedNfcTag(
    val uid: String,
    val tagType: String,
    val rawPayloadHex: String,
    val textContent: String?,
    val fullSummary: String
)

object Iso15693Parser {

    /**
     * Parses an ISO 15693 (NfcV) Tag or any other compatible NFC tag.
     */
    fun parseTag(tag: Tag): ParsedNfcTag {
        val uidBytes = tag.id
        // ISO 15693 UIDs are 8 bytes, often stored LSB first on tag
        val uidHex = bytesToHex(uidBytes)
        
        var isIso15693 = false
        val techList = tag.techList
        for (tech in techList) {
            if (tech.contains("NfcV", ignoreCase = true)) {
                isIso15693 = true
                break
            }
        }

        val tagType = if (isIso15693) "ISO 15693 (NfcV)" else techList.joinToString(", ") { it.substringAfterLast('.') }

        // Try reading NDEF first
        val ndefText = readNdefMessage(tag)

        // Try reading raw blocks via NfcV if available
        val nfcvData = if (isIso15693) readNfcVBlocks(tag) else null

        val rawPayloadHex = when {
            nfcvData != null && nfcvData.isNotEmpty() -> bytesToHex(nfcvData)
            ndefText != null -> bytesToHex(ndefText.toByteArray(StandardCharsets.UTF_8))
            else -> uidHex
        }

        val textContent = when {
            !ndefText.isNullOrBlank() -> ndefText
            nfcvData != null && nfcvData.isNotEmpty() -> parseAsciiIfPossible(nfcvData)
            else -> null
        }

        val fullSummary = buildSummary(uidHex, textContent, rawPayloadHex)

        return ParsedNfcTag(
            uid = uidHex,
            tagType = tagType,
            rawPayloadHex = rawPayloadHex,
            textContent = textContent,
            fullSummary = fullSummary
        )
    }

    private fun readNdefMessage(tag: Tag): String? {
        return try {
            val ndef = Ndef.get(tag) ?: return null
            ndef.connect()
            val ndefMessage = ndef.ndefMessage
            ndef.close()

            ndefMessage?.records?.joinToString(separator = "\n") { record ->
                val payload = record.payload ?: return@joinToString ""
                if (payload.isEmpty()) return@joinToString ""

                // Text record parsing (RFC 2046)
                if (record.tnf == android.nfc.NdefRecord.TNF_WELL_KNOWN &&
                    record.type.contentEquals(android.nfc.NdefRecord.RTD_TEXT)
                ) {
                    val statusByte = payload[0].toInt()
                    val isUtf16 = (statusByte and 0x80) != 0
                    val langCodeLength = statusByte and 0x3F
                    val textBytes = payload.copyOfRange(1 + langCodeLength, payload.size)
                    String(textBytes, if (isUtf16) StandardCharsets.UTF_16 else StandardCharsets.UTF_8)
                } else if (record.tnf == android.nfc.NdefRecord.TNF_WELL_KNOWN &&
                    record.type.contentEquals(android.nfc.NdefRecord.RTD_URI)
                ) {
                    String(payload, StandardCharsets.UTF_8)
                } else {
                    String(payload, StandardCharsets.UTF_8)
                }
            }?.trim()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Reads memory blocks from an ISO 15693 (NfcV) Tag.
     */
    private fun readNfcVBlocks(tag: Tag): ByteArray? {
        val nfcv = NfcV.get(tag) ?: return null
        return try {
            nfcv.connect()
            val outputStream = ByteArrayOutputStream()
            val uid = tag.id

            // Standard ISO 15693 Read Single Block Command:
            // Flags: 0x22 (High Data Rate + Addressed)
            // Command: 0x20 (Read Single Block)
            // UID: 8 bytes
            // Block Index: 1 byte
            for (blockIndex in 0 until 16) {
                val cmd = ByteArray(1 + 1 + uid.size + 1).apply {
                    this[0] = 0x22.toByte() // Flag: High data rate (0x02) | Addressed (0x20)
                    this[1] = 0x20.toByte() // Command: Read single block
                    System.arraycopy(uid, 0, this, 2, uid.size)
                    this[2 + uid.size] = blockIndex.toByte()
                }

                try {
                    val response = nfcv.transceive(cmd)
                    // Response: [0x00 (flags)] + [block data]
                    if (response != null && response.isNotEmpty() && response[0] == 0x00.toByte()) {
                        val blockData = response.copyOfRange(1, response.size)
                        outputStream.write(blockData)
                    } else {
                        // Try unaddressed mode as fallback for this block
                        val unaddressedCmd = byteArrayOf(0x02, 0x20, blockIndex.toByte())
                        val unaddressedResp = nfcv.transceive(unaddressedCmd)
                        if (unaddressedResp != null && unaddressedResp.isNotEmpty() && unaddressedResp[0] == 0x00.toByte()) {
                            outputStream.write(unaddressedResp.copyOfRange(1, unaddressedResp.size))
                        } else {
                            break
                        }
                    }
                } catch (e: Exception) {
                    break
                }
            }
            nfcv.close()

            val result = outputStream.toByteArray()
            if (result.isNotEmpty()) result else null
        } catch (e: Exception) {
            try {
                nfcv.close()
            } catch (ignored: Exception) {}
            null
        }
    }

    private fun parseAsciiIfPossible(bytes: ByteArray): String? {
        val filtered = bytes.filter { it in 32..126 || it == 10.toByte() || it == 13.toByte() }
        if (filtered.size > 2 && filtered.size >= bytes.size / 2) {
            return String(bytes, StandardCharsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
        }
        return null
    }

    private fun buildSummary(uid: String, text: String?, rawHex: String): String {
        return buildString {
            append("UID: ").append(uid)
            if (!text.isNullOrBlank()) {
                append(" | Inhalt: ").append(text)
            } else if (rawHex.isNotBlank()) {
                append(" | Hex: ").append(rawHex.take(32))
                if (rawHex.length > 32) append("...")
            }
        }
    }

    fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        val hexArray = "0123456789ABCDEF".toCharArray()
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            hexChars[i * 2] = hexArray[v ushr 4]
            hexChars[i * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }
}
