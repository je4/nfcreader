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
import android.util.Log
import ch.infoage.nfcreader.data.local.AppSettings
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

object Iso15693Writer {

    private const val TAG = "Iso15693Writer"

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
    fun writeFinnishData(
        tag: Tag,
        data: FinnishLibraryData,
        useAfiPassword: Boolean = false,
        afiPasswordHex: String = AppSettings.DEFAULT_AFI_PASSWORD,
        useWritePassword: Boolean = false,
        writePasswordHex: String = AppSettings.DEFAULT_WRITE_PASSWORD
    ): Result<ParsedNfcTag> {
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
                    val afiSuccess = writeAfiInternal(nfcv, uid, afiByte, useAfiPassword, afiPasswordHex)
                    if (!afiSuccess) {
                        Log.w(TAG, "AFI-Schreibbefehl konnte nicht ausgeführt werden.")
                    }
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
     * Toggles the AFI of an ISO 15693 tag:
     * 1) Reads current AFI from the tag via System Information
     * 2) Writes the toggled AFI (C2 -> 07, otherwise -> C2)
     * 3) Reads the entire tag data and returns [ParsedNfcTag]
     */
    fun toggleAfi(
        tag: Tag,
        useAfiPassword: Boolean = false,
        afiPasswordHex: String = AppSettings.DEFAULT_AFI_PASSWORD
    ): Result<ParsedNfcTag> {
        if (!isIso15693(tag)) {
            return Result.failure(IllegalArgumentException("Nur ISO 15693 (NfcV) Tags werden unterstützt."))
        }

        val nfcv = NfcV.get(tag) ?: return Result.failure(IllegalStateException("NfcV nicht verfügbar."))
        val uid = tag.id

        return try {
            nfcv.connect()

            // 1. Read current AFI directly from the connected tag
            val currentAfi = readAfiInternal(nfcv, uid)

            // Determine target AFI: C2 (Ausgeliehen) -> 07 (Gesichert), otherwise -> C2
            val targetAfiStr = if (currentAfi?.trim()?.uppercase() == "C2") "07" else "C2"
            val targetAfiByte = targetAfiStr.toInt(16).toByte()

            // 2. Write toggled AFI to tag
            val writeSuccess = writeAfiInternal(nfcv, uid, targetAfiByte, useAfiPassword, afiPasswordHex)
            if (!writeSuccess) {
                throw IllegalStateException("AFI-Schreibbefehl wurde vom Tag nicht akzeptiert.")
            }

            nfcv.close()

            // 3. Read tag completely as if 'Lesen' was pressed
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
     * Deactivates / resets the AFI password protection on an ISO 15693 tag:
     * 1) Authenticates using the configured AFI password or default 00000000
     * 2) Resets the AFI password on the chip to default 00000000
     * 3) Re-reads the complete tag and returns [ParsedNfcTag]
     */
    fun disableAfiPassword(
        tag: Tag,
        afiPasswordHex: String = AppSettings.DEFAULT_AFI_PASSWORD
    ): Result<ParsedNfcTag> {
        if (!isIso15693(tag)) {
            return Result.failure(IllegalArgumentException("Nur ISO 15693 (NfcV) Tags werden unterstützt."))
        }

        val nfcv = NfcV.get(tag) ?: return Result.failure(IllegalStateException("NfcV nicht verfügbar."))
        val uid = tag.id

        return try {
            nfcv.connect()

            // 1. Authenticate with current configured password or default 00000000
            var authSuccess = authenticateAfiPassword(nfcv, uid, afiPasswordHex)
            if (!authSuccess && afiPasswordHex != AppSettings.DEFAULT_AFI_PASSWORD) {
                authSuccess = authenticateAfiPassword(nfcv, uid, AppSettings.DEFAULT_AFI_PASSWORD)
            }

            // 2. Reset password to default 00000000
            writeAfiPasswordInternal(nfcv, uid, AppSettings.DEFAULT_AFI_PASSWORD)

            nfcv.close()

            // 3. Re-read tag completely
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
     * Reads AFI byte from an open NfcV connection using Get System Information (0x2B).
     */
    fun readAfiInternal(nfcv: NfcV, uid: ByteArray): String? {
        // 1. Try addressed mode: [Flags: 0x22, Cmd: 0x2B, UID (8 Bytes)]
        try {
            val sysInfoCmd = ByteArray(2 + uid.size).apply {
                this[0] = 0x22.toByte() // Flag: High data rate | Addressed
                this[1] = 0x2B.toByte() // Command: Get System Information
                System.arraycopy(uid, 0, this, 2, uid.size)
            }
            val sysInfoResp = nfcv.transceive(sysInfoCmd)
            if (sysInfoResp != null && sysInfoResp.isNotEmpty() && sysInfoResp[0] == 0x00.toByte()) {
                val afi = Iso15693Parser.parseAfiFromSystemInfo(sysInfoResp)
                if (afi != null) return afi
            }
        } catch (ignored: Exception) {}

        // 2. Try unaddressed mode as fallback: [Flags: 0x02, Cmd: 0x2B]
        try {
            val unaddressedSysInfoCmd = byteArrayOf(0x02, 0x2B)
            val unaddressedResp = nfcv.transceive(unaddressedSysInfoCmd)
            if (unaddressedResp != null && unaddressedResp.isNotEmpty() && unaddressedResp[0] == 0x00.toByte()) {
                val afi = Iso15693Parser.parseAfiFromSystemInfo(unaddressedResp)
                if (afi != null) return afi
            }
        } catch (ignored: Exception) {}

        return null
    }

    /**
     * Writes AFI value to an ISO 15693 tag.
     */
    fun writeAfi(
        tag: Tag,
        afiHex: String,
        useAfiPassword: Boolean = false,
        afiPasswordHex: String = AppSettings.DEFAULT_AFI_PASSWORD
    ): Result<String> {
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
            val success = writeAfiInternal(nfcv, uid, afiByte, useAfiPassword, afiPasswordHex)
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
     * Writes AFI byte using ISO 15693 Write AFI command (0x27) with optional password authentication
     * and protection enablement.
     */
    fun writeAfiInternal(
        nfcv: NfcV,
        uid: ByteArray,
        afiByte: Byte,
        useAfiPassword: Boolean = false,
        afiPasswordHex: String = AppSettings.DEFAULT_AFI_PASSWORD
    ): Boolean {
        if (useAfiPassword) {
            // 1. Authenticate with configured password first
            var authSuccess = authenticateAfiPassword(nfcv, uid, afiPasswordHex)
            if (!authSuccess && afiPasswordHex != AppSettings.DEFAULT_AFI_PASSWORD) {
                // If tag was not configured with this password yet, try authenticating with default 00000000
                val defaultAuth = authenticateAfiPassword(nfcv, uid, AppSettings.DEFAULT_AFI_PASSWORD)
                if (defaultAuth) {
                    writeAfiPasswordInternal(nfcv, uid, afiPasswordHex)
                    enableAfiProtectionInternal(nfcv, uid)
                    authSuccess = true
                }
            }

            // 2. Try writing AFI
            val writeSuccess = writeAfiDirect(nfcv, uid, afiByte)
            if (writeSuccess) {
                // If tag was unprotected previously, ensure password & protection are applied
                if (afiPasswordHex != AppSettings.DEFAULT_AFI_PASSWORD) {
                    writeAfiPasswordInternal(nfcv, uid, afiPasswordHex)
                    enableAfiProtectionInternal(nfcv, uid)
                }
                return true
            }

            // 3. If direct write failed, re-authenticate and retry
            if (!authSuccess) {
                authenticateAfiPassword(nfcv, uid, afiPasswordHex)
            }
            return writeAfiDirect(nfcv, uid, afiByte)
        } else {
            return writeAfiDirect(nfcv, uid, afiByte)
        }
    }

    /**
     * Performs standard ISO 15693 Write AFI (0x27) in addressed and unaddressed modes.
     */
    fun writeAfiDirect(nfcv: NfcV, uid: ByteArray, afiByte: Byte): Boolean {
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
     * Authenticates for EAS/AFI (Identifier 0x10) on NXP ICODE tags using GET RANDOM NUMBER (0xB2)
     * and SET PASSWORD (0xB3).
     */
    fun authenticateAfiPassword(nfcv: NfcV, uid: ByteArray, passwordHex: String): Boolean {
        val pwdBytes = AppSettings.hex32ToBytes(passwordHex, lsbFirst = false)
        val pwdBytesLsb = AppSettings.hex32ToBytes(passwordHex, lsbFirst = true)

        // 1. Get Random Number (0xB2)
        val randomResp = getRandomNumber(nfcv, uid) ?: return false
        if (randomResp.size < 3 || randomResp[0] != 0x00.toByte()) return false

        val rn0 = randomResp[1]
        val rn1 = randomResp[2]

        // 2. Try Set Password (0xB3) for EAS/AFI (0x10) with standard byte order
        val xorPwd = byteArrayOf(
            (pwdBytes[0].toInt() xor rn0.toInt()).toByte(),
            (pwdBytes[1].toInt() xor rn1.toInt()).toByte(),
            (pwdBytes[2].toInt() xor rn0.toInt()).toByte(),
            (pwdBytes[3].toInt() xor rn1.toInt()).toByte()
        )
        if (sendSetPassword(nfcv, uid, 0x10.toByte(), xorPwd)) {
            return true
        }

        // Try LSB order as fallback
        val xorPwdLsb = byteArrayOf(
            (pwdBytesLsb[0].toInt() xor rn0.toInt()).toByte(),
            (pwdBytesLsb[1].toInt() xor rn1.toInt()).toByte(),
            (pwdBytesLsb[2].toInt() xor rn0.toInt()).toByte(),
            (pwdBytesLsb[3].toInt() xor rn1.toInt()).toByte()
        )
        return sendSetPassword(nfcv, uid, 0x10.toByte(), xorPwdLsb)
    }

    /**
     * Queries a 16-bit random number from an NXP ICODE tag (Command 0xB2).
     */
    fun getRandomNumber(nfcv: NfcV, uid: ByteArray): ByteArray? {
        val addressedCmd = ByteArray(3 + uid.size).apply {
            this[0] = 0x22.toByte() // High data rate | Addressed
            this[1] = 0xB2.toByte() // GET RANDOM NUMBER
            this[2] = 0x04.toByte() // Mfg: NXP
            System.arraycopy(uid, 0, this, 3, uid.size)
        }
        try {
            val resp = nfcv.transceive(addressedCmd)
            if (resp != null && resp.isNotEmpty() && resp[0] == 0x00.toByte()) {
                return resp
            }
        } catch (ignored: Exception) {}

        val unaddressedCmd = byteArrayOf(0x02, 0xB2.toByte(), 0x04)
        return try {
            val resp = nfcv.transceive(unaddressedCmd)
            if (resp != null && resp.isNotEmpty() && resp[0] == 0x00.toByte()) resp else null
        } catch (ignored: Exception) {
            null
        }
    }

    /**
     * Sends the SET PASSWORD (0xB3) command.
     */
    fun sendSetPassword(nfcv: NfcV, uid: ByteArray, pwdId: Byte, xorPwd: ByteArray): Boolean {
        val addressedCmd = ByteArray(3 + uid.size + 1 + xorPwd.size).apply {
            this[0] = 0x22.toByte() // High data rate | Addressed
            this[1] = 0xB3.toByte() // SET PASSWORD
            this[2] = 0x04.toByte() // Mfg: NXP
            System.arraycopy(uid, 0, this, 3, uid.size)
            this[3 + uid.size] = pwdId
            System.arraycopy(xorPwd, 0, this, 4 + uid.size, xorPwd.size)
        }
        try {
            val resp = nfcv.transceive(addressedCmd)
            if (resp != null && resp.isNotEmpty() && resp[0] == 0x00.toByte()) {
                return true
            }
        } catch (ignored: Exception) {}

        val unaddressedCmd = ByteArray(3 + 1 + xorPwd.size).apply {
            this[0] = 0x02.toByte()
            this[1] = 0xB3.toByte()
            this[2] = 0x04.toByte()
            this[3] = pwdId
            System.arraycopy(xorPwd, 0, this, 4, xorPwd.size)
        }
        return try {
            val resp = nfcv.transceive(unaddressedCmd)
            resp != null && resp.isNotEmpty() && resp[0] == 0x00.toByte()
        } catch (ignored: Exception) {
            false
        }
    }

    /**
     * Writes a new 32-bit password to the tag using WRITE PASSWORD (0xB4).
     */
    fun writeAfiPasswordInternal(nfcv: NfcV, uid: ByteArray, newPasswordHex: String): Boolean {
        val pwdBytes = AppSettings.hex32ToBytes(newPasswordHex, lsbFirst = false)
        val pwdBytesLsb = AppSettings.hex32ToBytes(newPasswordHex, lsbFirst = true)

        val addressedCmd = ByteArray(3 + uid.size + 1 + 4).apply {
            this[0] = 0x22.toByte() // High data rate | Addressed
            this[1] = 0xB4.toByte() // WRITE PASSWORD
            this[2] = 0x04.toByte() // Mfg: NXP
            System.arraycopy(uid, 0, this, 3, uid.size)
            this[3 + uid.size] = 0x10.toByte() // EAS/AFI
            System.arraycopy(pwdBytes, 0, this, 4 + uid.size, 4)
        }
        try {
            val resp = nfcv.transceive(addressedCmd)
            if (resp != null && resp.isNotEmpty() && resp[0] == 0x00.toByte()) {
                return true
            }
        } catch (ignored: Exception) {}

        System.arraycopy(pwdBytesLsb, 0, addressedCmd, 4 + uid.size, 4)
        return try {
            val resp = nfcv.transceive(addressedCmd)
            resp != null && resp.isNotEmpty() && resp[0] == 0x00.toByte()
        } catch (ignored: Exception) {
            false
        }
    }

    /**
     * Enables password protection for AFI on NXP ICODE tags using PASSWORD PROTECT EAS/AFI (0xA6).
     */
    fun enableAfiProtectionInternal(nfcv: NfcV, uid: ByteArray): Boolean {
        val addressedCmd = ByteArray(3 + uid.size).apply {
            this[0] = 0x62.toByte() // High data rate | Addressed | Option flag
            this[1] = 0xA6.toByte() // PASSWORD PROTECT EAS/AFI
            this[2] = 0x04.toByte() // Mfg: NXP
            System.arraycopy(uid, 0, this, 3, uid.size)
        }
        try {
            val resp = nfcv.transceive(addressedCmd)
            if (resp != null && resp.isNotEmpty() && resp[0] == 0x00.toByte()) {
                return true
            }
        } catch (ignored: Exception) {}

        val unaddressedCmd = byteArrayOf(0x42.toByte(), 0xA6.toByte(), 0x04.toByte())
        return try {
            val resp = nfcv.transceive(unaddressedCmd)
            resp != null && resp.isNotEmpty() && resp[0] == 0x00.toByte()
        } catch (ignored: Exception) {
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
