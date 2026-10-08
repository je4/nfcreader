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
import ch.infoage.nfcreader.data.local.AppSettings
import ch.infoage.nfcreader.util.AppLogger
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
            AppLogger.w(TAG, "readTag: Tag is not ISO 15693 (NfcV)")
            return Result.failure(IllegalArgumentException("Nur ISO 15693 (NfcV) Tags werden unterstützt."))
        }
        AppLogger.i(TAG, "Reading ISO 15693 tag UID=${Iso15693Parser.bytesToHex(tag.id)}")
        return try {
            val parsed = Iso15693Parser.parseTag(tag)
            AppLogger.d(TAG, "Tag successfully read and parsed: UID=${parsed.uid}")
            Result.success(parsed)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to read ISO 15693 tag: ${e.message}", e)
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
            AppLogger.w(TAG, "writeFinnishData: Tag is not ISO 15693 (NfcV)")
            return Result.failure(IllegalArgumentException("Nur ISO 15693 (NfcV) Tags werden unterstützt."))
        }

        val nfcv = NfcV.get(tag) ?: return Result.failure(IllegalStateException("NfcV nicht verfügbar."))
        val uid = tag.id
        val uidHex = Iso15693Parser.bytesToHex(uid)
        AppLogger.i(TAG, "writeFinnishData started for UID $uidHex: ItemId=${data.itemId}, AFI=${data.afi}, PwdProtected=$useWritePassword")

        return try {
            nfcv.connect()

            val encodedBytes = FinnishDataModelParser.encode(data, size = 36)
            val blockSize = 4
            val numBlocks = encodedBytes.size / blockSize

            for (blockIndex in 0 until numBlocks) {
                val blockData = encodedBytes.copyOfRange(blockIndex * blockSize, (blockIndex + 1) * blockSize)
                val writeSuccess = writeSingleBlock(nfcv, uid, blockIndex, blockData)
                if (!writeSuccess) {
                    AppLogger.e(TAG, "Failed writing block $blockIndex: ${Iso15693Parser.bytesToHex(blockData)}")
                    throw IllegalStateException("Fehler beim Schreiben von Block $blockIndex.")
                }
                AppLogger.d(TAG, "Wrote block $blockIndex: ${Iso15693Parser.bytesToHex(blockData)}")
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
                        AppLogger.w(TAG, "AFI-Schreibbefehl konnte nicht ausgeführt werden.")
                    } else {
                        AppLogger.i(TAG, "AFI 0x${data.afi} successfully written")
                    }
                }
            }

            nfcv.close()

            // Re-read tag to return fresh parsed state
            val parsed = Iso15693Parser.parseTag(tag)
            AppLogger.i(TAG, "writeFinnishData completed successfully for UID $uidHex")
            Result.success(parsed)
        } catch (e: Exception) {
            AppLogger.e(TAG, "writeFinnishData failed for UID $uidHex: ${e.message}", e)
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
            AppLogger.w(TAG, "toggleAfi: Tag is not ISO 15693 (NfcV)")
            return Result.failure(IllegalArgumentException("Nur ISO 15693 (NfcV) Tags werden unterstützt."))
        }

        val nfcv = NfcV.get(tag) ?: return Result.failure(IllegalStateException("NfcV nicht verfügbar."))
        val uid = tag.id
        val uidHex = Iso15693Parser.bytesToHex(uid)
        AppLogger.i(TAG, "toggleAfi started for UID $uidHex")

        return try {
            nfcv.connect()

            // 1. Read current AFI directly from the connected tag
            val currentAfi = readAfiInternal(nfcv, uid)
            AppLogger.d(TAG, "toggleAfi: Current AFI is $currentAfi")

            // Determine target AFI: C2 (Ausgeliehen) -> 07 (Gesichert), otherwise -> C2
            val targetAfiStr = if (currentAfi?.trim()?.uppercase() == "C2") "07" else "C2"
            val targetAfiByte = targetAfiStr.toInt(16).toByte()
            AppLogger.i(TAG, "toggleAfi: Target AFI is $targetAfiStr")

            // 2. Write toggled AFI to tag
            val writeSuccess = writeAfiInternal(nfcv, uid, targetAfiByte, useAfiPassword, afiPasswordHex)
            if (!writeSuccess) {
                AppLogger.e(TAG, "toggleAfi: Failed writing toggled AFI $targetAfiStr")
                throw IllegalStateException("AFI-Schreibbefehl wurde vom Tag nicht akzeptiert.")
            }

            nfcv.close()

            // 3. Read tag completely as if 'Lesen' was pressed
            val parsed = Iso15693Parser.parseTag(tag)
            AppLogger.i(TAG, "toggleAfi completed successfully for UID $uidHex (New AFI: ${parsed.afi})")
            Result.success(parsed)
        } catch (e: Exception) {
            AppLogger.e(TAG, "toggleAfi failed for UID $uidHex: ${e.message}", e)
            try {
                nfcv.close()
            } catch (ignored: Exception) {}
            Result.failure(e)
        }
    }

    /**
     * Deactivates / resets the AFI password protection on an ISO 15693 tag:
     * 1) Authenticates using the configured AFI password or default 00000000 (with RF reconnect if failed)
     * 2) Resets the AFI password on the chip to default 00000000
     * 3) Re-reads the complete tag and returns [ParsedNfcTag]
     */
    fun disableAfiPassword(
        tag: Tag,
        afiPasswordHex: String = AppSettings.DEFAULT_AFI_PASSWORD
    ): Result<ParsedNfcTag> {
        if (!isIso15693(tag)) {
            AppLogger.w(TAG, "disableAfiPassword: Tag is not ISO 15693 (NfcV)")
            return Result.failure(IllegalArgumentException("Nur ISO 15693 (NfcV) Tags werden unterstützt."))
        }

        val nfcv = NfcV.get(tag) ?: return Result.failure(IllegalStateException("NfcV nicht verfügbar."))
        val uid = tag.id
        val uidHex = Iso15693Parser.bytesToHex(uid)
        AppLogger.i(TAG, "disableAfiPassword started for UID $uidHex")

        return try {
            nfcv.connect()

            // 1. Authenticate with current configured password or default 00000000
            var authSuccess = authenticateAfiPassword(nfcv, uid, afiPasswordHex)
            if (!authSuccess && afiPasswordHex != AppSettings.DEFAULT_AFI_PASSWORD) {
                AppLogger.d(TAG, "Authentication with configured pwd failed, reconnecting and trying default 00000000")
                reconnectNfcV(nfcv)
                authSuccess = authenticateAfiPassword(nfcv, uid, AppSettings.DEFAULT_AFI_PASSWORD)
            }
            AppLogger.d(TAG, "disableAfiPassword auth result: $authSuccess")

            // 2. Reset password to default 00000000
            var resetSuccess = if (authSuccess) {
                writeAfiPasswordInternal(nfcv, uid, AppSettings.DEFAULT_AFI_PASSWORD)
            } else {
                false
            }

            // If reset failed and auth was not established, reconnect and retry auth with default 00000000
            if (!resetSuccess && !authSuccess) {
                reconnectNfcV(nfcv)
                authSuccess = authenticateAfiPassword(nfcv, uid, AppSettings.DEFAULT_AFI_PASSWORD)
                if (authSuccess) {
                    resetSuccess = writeAfiPasswordInternal(nfcv, uid, AppSettings.DEFAULT_AFI_PASSWORD)
                }
            }

            // Verify if AFI can be written directly without password
            reconnectNfcV(nfcv)
            val testAfi = readAfiInternal(nfcv, uid)
            val canWriteAfi = if (testAfi != null) {
                val byteVal = try { testAfi.toInt(16).toByte() } catch (e: Exception) { null }
                if (byteVal != null) writeAfiDirect(nfcv, uid, byteVal) else false
            } else {
                false
            }

            if (!resetSuccess && !authSuccess && !canWriteAfi) {
                AppLogger.e(TAG, "disableAfiPassword: Could not reset AFI password on chip")
                throw IllegalStateException("AFI-Passwort konnte auf dem Tag nicht zurückgesetzt werden.")
            }

            nfcv.close()

            // 3. Re-read tag completely
            val parsed = Iso15693Parser.parseTag(tag)
            val updatedParsed = parsed.copy(
                isAfiPasswordProtected = false,
                transponderDetails = parsed.transponderDetails?.copy(isAfiPasswordProtected = false)
            )
            AppLogger.i(TAG, "disableAfiPassword completed successfully for UID $uidHex")
            Result.success(updatedParsed)
        } catch (e: Exception) {
            AppLogger.e(TAG, "disableAfiPassword failed for UID $uidHex: ${e.message}", e)
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
            AppLogger.w(TAG, "writeAfi: Tag is not ISO 15693 (NfcV)")
            return Result.failure(IllegalArgumentException("Nur ISO 15693 (NfcV) Tags werden unterstützt."))
        }

        val afiByte = try {
            afiHex.trim().toInt(16).toByte()
        } catch (e: Exception) {
            AppLogger.e(TAG, "writeAfi: Invalid AFI hex value '$afiHex'", e)
            return Result.failure(IllegalArgumentException("Ungültiger AFI-Hex-Wert: $afiHex"))
        }

        val nfcv = NfcV.get(tag) ?: return Result.failure(IllegalStateException("NfcV nicht verfügbar."))
        val uid = tag.id
        val uidHex = Iso15693Parser.bytesToHex(uid)
        AppLogger.i(TAG, "writeAfi started for UID $uidHex: AFI=0x$afiHex, usePwd=$useAfiPassword")

        return try {
            nfcv.connect()
            val success = writeAfiInternal(nfcv, uid, afiByte, useAfiPassword, afiPasswordHex)
            nfcv.close()
            if (success) {
                AppLogger.i(TAG, "writeAfi succeeded for UID $uidHex")
                Result.success(String.format("%02X", afiByte.toInt() and 0xFF))
            } else {
                AppLogger.e(TAG, "writeAfi rejected by tag for UID $uidHex")
                Result.failure(IllegalStateException("AFI-Schreibbefehl wurde vom Tag nicht akzeptiert."))
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "writeAfi failed for UID $uidHex: ${e.message}", e)
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
     * Helper function to reconnect the NfcV connection and perform RF reset on chip.
     */
    fun reconnectNfcV(nfcv: NfcV): Boolean {
        try {
            nfcv.close()
        } catch (ignored: Exception) {}
        try {
            Thread.sleep(40)
        } catch (ignored: Exception) {}
        return try {
            nfcv.connect()
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "reconnectNfcV failed: ${e.message}")
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
        if (!useAfiPassword) {
            AppLogger.d(TAG, "writeAfiInternal: Direct write (no password)")
            return writeAfiDirect(nfcv, uid, afiByte)
        }

        AppLogger.i(TAG, "writeAfiInternal: Attempting password-protected write with pwd=$afiPasswordHex")

        // 1. Try authenticating with configured password first
        var authenticated = authenticateAfiPassword(nfcv, uid, afiPasswordHex)

        // 2. If authentication with configured password failed, reconnect and try with default password (00000000)
        if (!authenticated && afiPasswordHex != AppSettings.DEFAULT_AFI_PASSWORD) {
            AppLogger.d(TAG, "Auth with $afiPasswordHex failed, reconnecting and attempting default password 00000000")
            reconnectNfcV(nfcv)
            val defaultAuth = authenticateAfiPassword(nfcv, uid, AppSettings.DEFAULT_AFI_PASSWORD)
            if (defaultAuth) {
                // In Security State via default password: write new password to chip
                val pwdWritten = writeAfiPasswordInternal(nfcv, uid, afiPasswordHex)
                if (pwdWritten) {
                    // Re-authenticate with the newly set password
                    authenticated = authenticateAfiPassword(nfcv, uid, afiPasswordHex)
                } else {
                    authenticated = true
                }
            }
        }

        // 3. Write AFI while in Security State (Write AFI must precede 0xA6 to avoid security state reset)
        var writeSuccess = writeAfiDirect(nfcv, uid, afiByte)

        // 4. If writing failed, reconnect, re-authenticate and retry write
        if (!writeSuccess) {
            AppLogger.w(TAG, "writeAfiDirect failed, reconnecting, re-authenticating and retrying write")
            reconnectNfcV(nfcv)
            authenticated = authenticateAfiPassword(nfcv, uid, afiPasswordHex)
            if (authenticated) {
                writeSuccess = writeAfiDirect(nfcv, uid, afiByte)
            }
        }

        // 5. Ensure hardware AFI protection is enabled on chip after successful authentication/write
        if (authenticated && writeSuccess) {
            AppLogger.d(TAG, "Enabling hardware AFI protection after successful write")
            enableAfiProtectionInternal(nfcv, uid)
        }

        // 6. If tag was previously unprotected and write succeeded without prior authentication,
        // establish authentication, set password, and enable protection now
        if (writeSuccess && !authenticated) {
            reconnectNfcV(nfcv)
            val authDef = authenticateAfiPassword(nfcv, uid, AppSettings.DEFAULT_AFI_PASSWORD)
            if (authDef) {
                if (afiPasswordHex != AppSettings.DEFAULT_AFI_PASSWORD) {
                    writeAfiPasswordInternal(nfcv, uid, afiPasswordHex)
                    authenticated = authenticateAfiPassword(nfcv, uid, afiPasswordHex)
                }
                enableAfiProtectionInternal(nfcv, uid)
            } else {
                val authCfg = authenticateAfiPassword(nfcv, uid, afiPasswordHex)
                if (authCfg) {
                    enableAfiProtectionInternal(nfcv, uid)
                }
            }
        }

        AppLogger.i(TAG, "writeAfiInternal result: writeSuccess=$writeSuccess, authenticated=$authenticated")
        return writeSuccess
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
     * and SET PASSWORD (0xB3). Standard for NXP ICODE SLIX and SLIX2 tags.
     */
    fun authenticateAfiPassword(nfcv: NfcV, uid: ByteArray, passwordHex: String): Boolean {
        val pwdBytesLsb = AppSettings.hex32ToBytes(passwordHex, lsbFirst = true)
        val pwdBytesMsb = AppSettings.hex32ToBytes(passwordHex, lsbFirst = false)

        // 1. Get Random Number (0xB2)
        val randomResp = getRandomNumber(nfcv, uid) ?: return false
        if (randomResp.size < 3 || randomResp[0] != 0x00.toByte()) return false

        val rn0 = randomResp[1]
        val rn1 = randomResp[2]

        // 2. Standard ISO 15693 LSB-first XOR calculation:
        // XOR_Password[31:0] = Password[31:0] XOR {Random_Number[15:0], Random_Number[15:0]}
        val xorPwdLsb = byteArrayOf(
            (pwdBytesLsb[0].toInt() xor rn0.toInt()).toByte(),
            (pwdBytesLsb[1].toInt() xor rn1.toInt()).toByte(),
            (pwdBytesLsb[2].toInt() xor rn0.toInt()).toByte(),
            (pwdBytesLsb[3].toInt() xor rn1.toInt()).toByte()
        )

        // Try standard ICODE SLIX / SLIX2 format (with password identifier 0x10 for EAS/AFI, LSB)
        if (sendSetPasswordWithId(nfcv, uid, 0x10.toByte(), xorPwdLsb)) {
            AppLogger.d(TAG, "Authenticated successfully via ICODE format (LSB, pwdId 0x10)")
            return true
        }

        // Try MSB order fallback after reconnect
        reconnectNfcV(nfcv)
        val randomResp2 = getRandomNumber(nfcv, uid) ?: return false
        if (randomResp2.size < 3 || randomResp2[0] != 0x00.toByte()) return false
        val rn0_2 = randomResp2[1]
        val rn1_2 = randomResp2[2]

        val xorPwdMsb = byteArrayOf(
            (pwdBytesMsb[0].toInt() xor rn0_2.toInt()).toByte(),
            (pwdBytesMsb[1].toInt() xor rn1_2.toInt()).toByte(),
            (pwdBytesMsb[2].toInt() xor rn0_2.toInt()).toByte(),
            (pwdBytesMsb[3].toInt() xor rn1_2.toInt()).toByte()
        )

        if (sendSetPasswordWithId(nfcv, uid, 0x10.toByte(), xorPwdMsb)) {
            AppLogger.d(TAG, "Authenticated successfully via ICODE format (MSB, pwdId 0x10)")
            return true
        }

        // Fallback without pwdId (for non-standard ICs, with clean reconnect)
        reconnectNfcV(nfcv)
        val randomResp3 = getRandomNumber(nfcv, uid) ?: return false
        if (randomResp3.size >= 3 && randomResp3[0] == 0x00.toByte()) {
            val rn0_3 = randomResp3[1]
            val rn1_3 = randomResp3[2]
            val xorPwdNoId = byteArrayOf(
                (pwdBytesLsb[0].toInt() xor rn0_3.toInt()).toByte(),
                (pwdBytesLsb[1].toInt() xor rn1_3.toInt()).toByte(),
                (pwdBytesLsb[2].toInt() xor rn0_3.toInt()).toByte(),
                (pwdBytesLsb[3].toInt() xor rn1_3.toInt()).toByte()
            )
            if (sendSetPassword(nfcv, uid, xorPwdNoId)) {
                AppLogger.d(TAG, "Authenticated successfully via legacy format without pwdId")
                return true
            }
        }

        return false
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
     * Sends the SET PASSWORD (0xB3) command for ICODE SLIX (without Password Identifier).
     */
    fun sendSetPassword(nfcv: NfcV, uid: ByteArray, xorPwd: ByteArray): Boolean {
        // 1. Addressed mode: [Flags: 0x22, Cmd: 0xB3, Mfg: 0x04, UID (8 Bytes), XOR_PWD (4 Bytes)]
        val addressedCmd = ByteArray(3 + uid.size + xorPwd.size).apply {
            this[0] = 0x22.toByte() // High data rate | Addressed
            this[1] = 0xB3.toByte() // SET PASSWORD
            this[2] = 0x04.toByte() // Mfg: NXP
            System.arraycopy(uid, 0, this, 3, uid.size)
            System.arraycopy(xorPwd, 0, this, 3 + uid.size, xorPwd.size)
        }
        try {
            val resp = nfcv.transceive(addressedCmd)
            if (resp != null && resp.isNotEmpty() && resp[0] == 0x00.toByte()) {
                return true
            }
        } catch (ignored: Exception) {}

        // 2. Addressed with Option flag (0x62)
        val addressedCmdOpt = addressedCmd.clone().apply {
            this[0] = 0x62.toByte()
        }
        try {
            val resp = nfcv.transceive(addressedCmdOpt)
            if (resp != null && resp.isNotEmpty() && resp[0] == 0x00.toByte()) {
                return true
            }
        } catch (ignored: Exception) {}

        // 3. Unaddressed mode: [Flags: 0x02, Cmd: 0xB3, Mfg: 0x04, XOR_PWD (4 Bytes)]
        val unaddressedCmd = ByteArray(3 + xorPwd.size).apply {
            this[0] = 0x02.toByte()
            this[1] = 0xB3.toByte()
            this[2] = 0x04.toByte()
            System.arraycopy(xorPwd, 0, this, 3, xorPwd.size)
        }
        return try {
            val resp = nfcv.transceive(unaddressedCmd)
            resp != null && resp.isNotEmpty() && resp[0] == 0x00.toByte()
        } catch (ignored: Exception) {
            false
        }
    }

    /**
     * Sends the SET PASSWORD (0xB3) command for ICODE SLIX2 (with Password Identifier).
     */
    fun sendSetPasswordWithId(nfcv: NfcV, uid: ByteArray, pwdId: Byte, xorPwd: ByteArray): Boolean {
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

        // Addressed with Option flag (0x62)
        val addressedCmdOpt = addressedCmd.clone().apply {
            this[0] = 0x62.toByte()
        }
        try {
            val resp = nfcv.transceive(addressedCmdOpt)
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
     * Standard for NXP ICODE SLIX and SLIX2 (with pwdId 0x10 for EAS/AFI).
     */
    fun writeAfiPasswordInternal(nfcv: NfcV, uid: ByteArray, newPasswordHex: String): Boolean {
        val pwdBytesLsb = AppSettings.hex32ToBytes(newPasswordHex, lsbFirst = true)
        val pwdBytesMsb = AppSettings.hex32ToBytes(newPasswordHex, lsbFirst = false)

        // 1. Standard ICODE format (with pwdId 0x10) - Addressed mode with Option flag (0x62)
        val addressedCmdOptWithId = ByteArray(3 + uid.size + 1 + 4).apply {
            this[0] = 0x62.toByte() // High data rate | Addressed | Option
            this[1] = 0xB4.toByte() // WRITE PASSWORD
            this[2] = 0x04.toByte() // Mfg: NXP
            System.arraycopy(uid, 0, this, 3, uid.size)
            this[3 + uid.size] = 0x10.toByte() // EAS/AFI
            System.arraycopy(pwdBytesLsb, 0, this, 4 + uid.size, 4)
        }
        try {
            val resp = nfcv.transceive(addressedCmdOptWithId)
            if (resp != null && resp.isNotEmpty() && resp[0] == 0x00.toByte()) {
                return true
            }
        } catch (ignored: Exception) {}

        // 2. Standard ICODE format (with pwdId 0x10) - Addressed mode without Option flag (0x22)
        val addressedCmdWithId = addressedCmdOptWithId.clone().apply {
            this[0] = 0x22.toByte()
        }
        try {
            val resp = nfcv.transceive(addressedCmdWithId)
            if (resp != null && resp.isNotEmpty() && resp[0] == 0x00.toByte()) {
                return true
            }
        } catch (ignored: Exception) {}

        // 3. Fallback MSB with pwdId 0x10
        System.arraycopy(pwdBytesMsb, 0, addressedCmdOptWithId, 4 + uid.size, 4)
        try {
            val resp = nfcv.transceive(addressedCmdOptWithId)
            if (resp != null && resp.isNotEmpty() && resp[0] == 0x00.toByte()) {
                return true
            }
        } catch (ignored: Exception) {}

        // 4. Unaddressed mode with pwdId 0x10
        val unaddressedCmdWithId = ByteArray(4 + 4).apply {
            this[0] = 0x42.toByte() // Option flag
            this[1] = 0xB4.toByte()
            this[2] = 0x04.toByte()
            this[3] = 0x10.toByte()
            System.arraycopy(pwdBytesLsb, 0, this, 4, 4)
        }
        try {
            val resp = nfcv.transceive(unaddressedCmdWithId)
            if (resp != null && resp.isNotEmpty() && resp[0] == 0x00.toByte()) {
                return true
            }
        } catch (ignored: Exception) {}

        // 5. Fallback without pwdId (for non-standard ICs)
        val addressedCmdOptNoId = ByteArray(3 + uid.size + 4).apply {
            this[0] = 0x62.toByte() // High data rate | Addressed | Option
            this[1] = 0xB4.toByte() // WRITE PASSWORD
            this[2] = 0x04.toByte() // Mfg: NXP
            System.arraycopy(uid, 0, this, 3, uid.size)
            System.arraycopy(pwdBytesLsb, 0, this, 3 + uid.size, 4)
        }
        try {
            val resp = nfcv.transceive(addressedCmdOptNoId)
            if (resp != null && resp.isNotEmpty() && resp[0] == 0x00.toByte()) {
                return true
            }
        } catch (ignored: Exception) {}

        return false
    }

    /**
     * Enables password protection for AFI on NXP ICODE tags using PASSWORD PROTECT EAS/AFI (0xA6).
     */
    fun enableAfiProtectionInternal(nfcv: NfcV, uid: ByteArray): Boolean {
        // 1. Addressed mode with Option flag (0x62): Standard for NXP ICODE SLIX Password Protect
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

        // 2. Addressed mode without Option flag (0x22) as fallback
        val addressedCmdNoOpt = ByteArray(3 + uid.size).apply {
            this[0] = 0x22.toByte() // High data rate | Addressed
            this[1] = 0xA6.toByte() // PASSWORD PROTECT EAS/AFI
            this[2] = 0x04.toByte() // Mfg: NXP
            System.arraycopy(uid, 0, this, 3, uid.size)
        }
        try {
            val resp = nfcv.transceive(addressedCmdNoOpt)
            if (resp != null && resp.isNotEmpty() && resp[0] == 0x00.toByte()) {
                return true
            }
        } catch (ignored: Exception) {}

        // 3. Unaddressed mode with Option flag (0x42)
        val unaddressedCmd = byteArrayOf(0x42.toByte(), 0xA6.toByte(), 0x04.toByte())
        try {
            val resp = nfcv.transceive(unaddressedCmd)
            if (resp != null && resp.isNotEmpty() && resp[0] == 0x00.toByte()) {
                return true
            }
        } catch (ignored: Exception) {}

        // 4. Unaddressed mode without Option flag (0x02)
        val unaddressedCmdNoOpt = byteArrayOf(0x02.toByte(), 0xA6.toByte(), 0x04.toByte())
        return try {
            val resp = nfcv.transceive(unaddressedCmdNoOpt)
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
