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
import android.nfc.tech.NfcV
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

object Iso15693Writer {

    /**
     * Checks whether the given Tag supports ISO 15693 (NfcV).
     */
    fun isIso15693(tag: Tag): Boolean {
        return tag.techList.any { it.contains("NfcV", ignoreCase = true) }
    }

    /**
     * Reads memory blocks and AFI from an ISO 15693 tag.
     */
    fun readTag(tag: Tag): Result<ParsedNfcTag> {
        if (!isIso15693(tag)) {
            return Result.failure(IllegalArgumentException("Nur ISO 15693 (NfcV) Tags werden unterstützt."))
        }
        return try {
            val parsed = Iso15693Parser.parseTag(tag)
            Result.success(parsed)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Writes Finnish Data Model blocks (including computed CRC) to an ISO 15693 tag.
     * Also updates AFI if specified.
     */
    fun writeFinnishData(tag: Tag, data: FinnishLibraryData): Result<ParsedNfcTag> {
        if (!isIso15693(tag)) {
            return Result.failure(IllegalArgumentException("Nur ISO 15693 (NfcV) Tags werden unterstützt."))
        }

        val nfcv = NfcV.get(tag) ?: return Result.failure(IllegalStateException("NfcV nicht verfügbar."))
        val uid = tag.id

        return try {
            nfcv.connect()

            val encodedBytes = FinnishDataModelParser.encode(data, size = 36)
            val blockSize = 4
            val numBlocks = encodedBytes.size / blockSize

            for (blockIndex in 0 until numBlocks) {
                val blockData = encodedBytes.copyOfRange(blockIndex * blockSize, (blockIndex + 1) * blockSize)
                val writeSuccess = writeSingleBlock(nfcv, uid, blockIndex, blockData)
                if (!writeSuccess) {
                    throw IllegalStateException("Fehler beim Schreiben von Block $blockIndex.")
                }
            }

            // Write AFI if specified
            if (data.afi.isNotBlank()) {
                val afiByte = try {
                    data.afi.toInt(16).toByte()
                } catch (e: Exception) {
                    null
                }
                if (afiByte != null) {
                    writeAfiInternal(nfcv, uid, afiByte)
                }
            }

            nfcv.close()

            // Re-read tag to return fresh parsed state
            val parsed = Iso15693Parser.parseTag(tag)
            Result.success(parsed)
        } catch (e: Exception) {
            try {
                nfcv.close()
            } catch (ignored: Exception) {}
            Result.failure(e)
        }
    }

    /**
     * Writes AFI value to an ISO 15693 tag.
     */
    fun writeAfi(tag: Tag, afiHex: String): Result<String> {
        if (!isIso15693(tag)) {
            return Result.failure(IllegalArgumentException("Nur ISO 15693 (NfcV) Tags werden unterstützt."))
        }

        val afiByte = try {
            afiHex.trim().toInt(16).toByte()
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("Ungültiger AFI-Hex-Wert: $afiHex"))
        }

        val nfcv = NfcV.get(tag) ?: return Result.failure(IllegalStateException("NfcV nicht verfügbar."))
        val uid = tag.id

        return try {
            nfcv.connect()
            val success = writeAfiInternal(nfcv, uid, afiByte)
            nfcv.close()
            if (success) {
                Result.success(String.format("%02X", afiByte.toInt() and 0xFF))
            } else {
                Result.failure(IllegalStateException("AFI-Schreibbefehl wurde vom Tag nicht akzeptiert."))
            }
        } catch (e: Exception) {
            try {
                nfcv.close()
            } catch (ignored: Exception) {}
            Result.failure(e)
        }
    }

    /**
     * Writes a single 4-byte block using ISO 15693 Write Single Block command (0x21).
     */
    private fun writeSingleBlock(nfcv: NfcV, uid: ByteArray, blockIndex: Int, blockData: ByteArray): Boolean {
        // 1. Try addressed mode: [Flags: 0x22, Cmd: 0x21, UID (8 Bytes), BlockNo, BlockData (4 Bytes)]
        val addressedCmd = ByteArray(1 + 1 + uid.size + 1 + blockData.size).apply {
            this[0] = 0x22.toByte() // High data rate | Addressed
            this[1] = 0x21.toByte() // Write Single Block
            System.arraycopy(uid, 0, this, 2, uid.size)
            this[2 + uid.size] = blockIndex.toByte()
            System.arraycopy(blockData, 0, this, 3 + uid.size, blockData.size)
        }

        try {
            val resp = nfcv.transceive(addressedCmd)
            if (resp != null && resp.isNotEmpty() && resp[0] == 0x00.toByte()) {
                return true
            }
        } catch (ignored: Exception) {}

        // 2. Try unaddressed mode as fallback: [Flags: 0x02, Cmd: 0x21, BlockNo, BlockData]
        val unaddressedCmd = ByteArray(1 + 1 + 1 + blockData.size).apply {
            this[0] = 0x02.toByte() // High data rate
            this[1] = 0x21.toByte() // Write Single Block
            this[2] = blockIndex.toByte()
            System.arraycopy(blockData, 0, this, 3, blockData.size)
        }

        return try {
            val unaddressedResp = nfcv.transceive(unaddressedCmd)
            unaddressedResp != null && unaddressedResp.isNotEmpty() && unaddressedResp[0] == 0x00.toByte()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Writes AFI byte using ISO 15693 Write AFI command (0x27).
     */
    private fun writeAfiInternal(nfcv: NfcV, uid: ByteArray, afiByte: Byte): Boolean {
        // 1. Try addressed mode: [Flags: 0x22, Cmd: 0x27, UID (8 Bytes), AFI]
        val addressedCmd = ByteArray(1 + 1 + uid.size + 1).apply {
            this[0] = 0x22.toByte() // High data rate | Addressed
            this[1] = 0x27.toByte() // Write AFI
            System.arraycopy(uid, 0, this, 2, uid.size)
            this[2 + uid.size] = afiByte
        }

        try {
            val resp = nfcv.transceive(addressedCmd)
            if (resp != null && resp.isNotEmpty() && resp[0] == 0x00.toByte()) {
                return true
            }
        } catch (ignored: Exception) {}

        // 2. Try unaddressed mode fallback: [Flags: 0x02, Cmd: 0x27, AFI]
        val unaddressedCmd = byteArrayOf(0x02, 0x27, afiByte)
        return try {
            val unaddressedResp = nfcv.transceive(unaddressedCmd)
            unaddressedResp != null && unaddressedResp.isNotEmpty() && unaddressedResp[0] == 0x00.toByte()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Formats raw block bytes into a readable Hex & ASCII dump representation
     * (analogous to Tag Inspector in Library-RFID-Tools).
     */
    fun formatHexDump(rawBytes: ByteArray?, blockSize: Int = 4): String {
        if (rawBytes == null || rawBytes.isEmpty()) {
            return "Keine Rohdaten verfügbar."
        }

        val totalBlocks = (rawBytes.size + blockSize - 1) / blockSize
        val sb = StringBuilder()

        for (block in 0 until totalBlocks) {
            val offset = block * blockSize
            val end = minOf(offset + blockSize, rawBytes.size)
            val chunk = rawBytes.copyOfRange(offset, end)

            val hexPart = chunk.joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }
            val paddedHex = hexPart.padEnd(blockSize * 3 - 1)

            val asciiPart = chunk.map { b ->
                val c = b.toInt() and 0xFF
                if (c in 32..126) c.toChar() else '.'
            }.joinToString("")

            sb.append(String.format("Block %02d:  %s  | %s\n", block, paddedHex, asciiPart))
        }

        return sb.toString().trimEnd()
    }
}
