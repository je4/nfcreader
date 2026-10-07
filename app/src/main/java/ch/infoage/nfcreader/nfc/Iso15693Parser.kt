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
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Explizite Kennzeichnung des Transponder-Identifikatortyps nach ISO/IEC 15963.
 */
enum class TagIdentifierType {
    /** 64-Bit Unique Identifier für HF-Transponder (ISO/IEC 15693 / NfcV), beginnt typischerweise mit 0xE0. */
    ISO15693_UID,
    /** Tag Identifier Bank 02 für UHF-Transponder (EPC Gen 2 / ISO/IEC 18000-6C / GS1 TDS), beginnt typischerweise mit 0xE2. */
    EPC_GEN2_TID,
    /** Generischer oder herstellerspezifischer Identifikator. */
    GENERIC
}

data class EpcTidDetails(
    val allocationClass: String = "E2",
    val hasXtid: Boolean,
    val hasSecurity: Boolean,
    val hasFileOpen: Boolean,
    val mdidInt: Int,
    val mdidHex: String,
    val mdidBinary: String,
    val manufacturerName: String,
    val manufacturerUrl: String? = null,
    val tmnHex: String,
    val modelName: String?,
    val productUrl: String? = null,
    val serialNumberHex: String? = null
)

data class Iso15693Details(
    val mfgCodeHex: String,
    val manufacturerName: String,
    val productCodeHex: String,
    val modelName: String?,
    val serialNumberHex: String
)

data class TransponderDetails(
    val identifierType: TagIdentifierType,
    val epcTid: EpcTidDetails? = null,
    val iso15693: Iso15693Details? = null,
    val formattedSummary: String
)

data class ChipInfo(
    val tmnHex: String,
    val tmnBinary: String,
    val modelName: String,
    val productUrl: String? = null
)

data class MaskDesigner(
    val mdid: String,
    val mdidInt: Int,
    val manufacturer: String,
    val manufacturerUrl: String? = null,
    val chips: List<ChipInfo> = emptyList()
)

data class ParsedNfcTag(
    val uid: String,
    val tagType: String,
    val rawPayloadHex: String,
    val textContent: String?,
    val fullSummary: String,
    val libraryData: FinnishLibraryData? = null,
    val afi: String? = null,
    val identifierType: TagIdentifierType = TagIdentifierType.GENERIC,
    val transponderDetails: TransponderDetails? = null
)

data class NfcVData(
    val blocks: ByteArray?,
    val afiHex: String? = null
)

object Iso15693Parser {

    /**
     * Normalisiert eine UID / Bytefolge, indem ISO 15693 (NfcV) UIDs von LSB-first in MSB-first umgekehrt werden.
     */
    fun normalizeUid(rawBytes: ByteArray, isIso15693: Boolean = false): ByteArray {
        if (rawBytes.size == 8) {
            val lastByte = rawBytes.last().toInt() and 0xFF
            val firstByte = rawBytes.first().toInt() and 0xFF
            if ((lastByte == 0xE0 && firstByte != 0xE0) || (isIso15693 && lastByte == 0xE0)) {
                return rawBytes.reversedArray()
            }
        }
        return rawBytes
    }

    /**
     * Normalisiert einen UID-Hex-String, falls dieser in LSB-first Übertragungsreihenfolge (endet mit E0) vorliegt.
     */
    fun normalizeUid(uidHex: String): String {
        val clean = uidHex.replace(":", "").replace(" ", "").trim().uppercase()
        if (clean.length == 16 && clean.endsWith("E0") && !clean.startsWith("E0")) {
            return clean.chunked(2).reversed().joinToString("")
        }
        return clean
    }

    /**
     * Parses an ISO 15693 (NfcV) Tag or any other compatible NFC tag.
     */
    fun parseTag(tag: Tag): ParsedNfcTag {
        val rawUidBytes = tag.id
        
        var isIso15693 = false
        val techList = tag.techList
        for (tech in techList) {
            if (tech.contains("NfcV", ignoreCase = true)) {
                isIso15693 = true
                break
            }
        }

        // ISO 15693 UIDs are transmitted LSB first over the air; normalize to MSB first representation
        val uidBytes = normalizeUid(rawUidBytes, isIso15693)
        val uidHex = bytesToHex(uidBytes)

        val tagType = if (isIso15693) resolveIso15693TagType(uidHex) else techList.joinToString(", ") { it.substringAfterLast('.') }

        // Try reading NDEF first
        val ndefText = readNdefMessage(tag)

        // Try reading raw blocks and AFI via NfcV if available
        val nfcvData = if (isIso15693) readNfcVData(tag) else null
        val rawBlocks = nfcvData?.blocks
        val afiHex = nfcvData?.afiHex

        // Try parsing Finnish Library Data Model if raw NFC-V bytes available
        val libraryData = if (rawBlocks != null && rawBlocks.isNotEmpty()) {
            FinnishDataModelParser.parse(uidHex, rawBlocks, afi = afiHex)
        } else null

        val rawPayloadHex = when {
            rawBlocks != null && rawBlocks.isNotEmpty() -> bytesToHex(rawBlocks)
            ndefText != null -> bytesToHex(ndefText.toByteArray(StandardCharsets.UTF_8))
            else -> uidHex
        }

        val textContent = when {
            libraryData != null && !libraryData.isTagEmpty -> libraryData.toFormattedString()
            !ndefText.isNullOrBlank() -> ndefText
            rawBlocks != null && rawBlocks.isNotEmpty() -> parseAsciiIfPossible(rawBlocks)
            else -> null
        }

        val fullSummary = buildSummary(uidHex, textContent, rawPayloadHex, libraryData)
        val identifierType = resolveIdentifierType(uidHex)
        val transponderDetails = parseTransponderDetails(uidHex)

        return ParsedNfcTag(
            uid = uidHex,
            tagType = tagType,
            rawPayloadHex = rawPayloadHex,
            textContent = textContent,
            fullSummary = fullSummary,
            libraryData = libraryData,
            afi = afiHex,
            identifierType = identifierType,
            transponderDetails = transponderDetails
        )
    }

    /**
     * Bestimmt den [TagIdentifierType] anhand des Hex-Präfixes nach ISO/IEC 15963.
     */
    fun resolveIdentifierType(identifierHex: String): TagIdentifierType {
        val clean = normalizeUid(identifierHex)
        return when {
            clean.startsWith("E0") -> TagIdentifierType.ISO15693_UID
            clean.startsWith("E2") -> TagIdentifierType.EPC_GEN2_TID
            else -> TagIdentifierType.GENERIC
        }
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
     * Reads memory blocks and AFI from an ISO 15693 (NfcV) Tag.
     */
    private fun readNfcVData(tag: Tag): NfcVData? {
        val nfcv = NfcV.get(tag) ?: return null
        return try {
            nfcv.connect()
            val uid = tag.id

            // 1. Read System Information to extract AFI (Application Family Identifier / Ausleihstatus)
            var afiHex: String? = null
            try {
                val sysInfoCmd = ByteArray(2 + uid.size).apply {
                    this[0] = 0x22.toByte() // Flag: High data rate (0x02) | Addressed (0x20)
                    this[1] = 0x2B.toByte() // Command: Get System Information
                    System.arraycopy(uid, 0, this, 2, uid.size)
                }
                val sysInfoResp = nfcv.transceive(sysInfoCmd)
                if (sysInfoResp != null && sysInfoResp.isNotEmpty() && sysInfoResp[0] == 0x00.toByte()) {
                    afiHex = parseAfiFromSystemInfo(sysInfoResp)
                } else {
                    // Try unaddressed mode as fallback
                    val unaddressedSysInfoCmd = byteArrayOf(0x02, 0x2B)
                    val unaddressedResp = nfcv.transceive(unaddressedSysInfoCmd)
                    if (unaddressedResp != null && unaddressedResp.isNotEmpty() && unaddressedResp[0] == 0x00.toByte()) {
                        afiHex = parseAfiFromSystemInfo(unaddressedResp)
                    }
                }
            } catch (ignored: Exception) {
                // Not all chips support Get System Information; continue with block reading
            }

            // 2. Read memory blocks
            val outputStream = ByteArrayOutputStream()
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

            val blocks = outputStream.toByteArray()
            NfcVData(
                blocks = if (blocks.isNotEmpty()) blocks else null,
                afiHex = afiHex
            )
        } catch (e: Exception) {
            try {
                nfcv.close()
            } catch (ignored: Exception) {}
            null
        }
    }

    /**
     * Extrahiert den AFI-Wert (Hex-String, z. B. "07" oder "C2") aus einer ISO 15693 Get System Information Antwort.
     */
    fun parseAfiFromSystemInfo(response: ByteArray): String? {
        if (response.size < 10) return null
        if (response[0] != 0x00.toByte()) return null

        val infoFlags = response[1].toInt() and 0xFF
        var offset = 10 // Byte 0: Flags, Byte 1: InfoFlags, Bytes 2..9: UID (8 Bytes)

        // Bit 0 (0x01): DSFID unterstützt und vorhanden (1 Byte)
        if ((infoFlags and 0x01) != 0) {
            offset += 1
        }

        // Bit 1 (0x02): AFI unterstützt und vorhanden (1 Byte)
        if ((infoFlags and 0x02) != 0 && response.size > offset) {
            val afiByte = response[offset]
            return String.format("%02X", afiByte.toInt() and 0xFF)
        }

        return null
    }

    val maskDesigners: List<MaskDesigner> by lazy {
        loadMaskDesigners()
    }

    private fun loadMaskDesigners(): List<MaskDesigner> {
        return try {
            val stream = Iso15693Parser::class.java.getResourceAsStream("/mdid_list.json")
                ?: Iso15693Parser::class.java.classLoader?.getResourceAsStream("mdid_list.json")
                ?: Thread.currentThread().contextClassLoader?.getResourceAsStream("mdid_list.json")
                ?: java.io.File("mdid_list.json").takeIf { it.exists() }?.inputStream()
                ?: java.io.File("app/src/main/resources/mdid_list.json").takeIf { it.exists() }?.inputStream()
                ?: return emptyList()

            val jsonText = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val json = JSONObject(jsonText)
            val array = json.optJSONArray("registeredMaskDesigners") ?: return emptyList()
            val list = ArrayList<MaskDesigner>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val mdidStr = obj.optString("mdid", "").trim()
                val mdidInt = mdidStr.toIntOrNull(2) ?: -1
                val manufacturer = obj.optString("manufacturer", "").trim()
                val manufacturerUrl = if (obj.has("manufacturerUrl")) obj.optString("manufacturerUrl") else null
                val chipsList = mutableListOf<ChipInfo>()
                val chipsArray = obj.optJSONArray("chips")
                if (chipsArray != null) {
                    for (j in 0 until chipsArray.length()) {
                        val chipObj = chipsArray.optJSONObject(j) ?: continue
                        chipsList.add(
                            ChipInfo(
                                tmnHex = chipObj.optString("tmnHex", "").trim().uppercase(),
                                tmnBinary = chipObj.optString("tmnBinary", "").trim(),
                                modelName = chipObj.optString("modelName", "").trim(),
                                productUrl = if (chipObj.has("productUrl")) chipObj.optString("productUrl") else null
                            )
                        )
                    }
                }
                list.add(MaskDesigner(mdidStr, mdidInt, manufacturer, manufacturerUrl, chipsList))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Ermittelt eine kompakte Transponder-Typbezeichnung (Hersteller, Modell und Protokoll)
     * anhand der 64-Bit UID nach ISO/IEC 15693 (Präfix E0) oder EPC Gen2 TID (Präfix E2, MDID/TMN).
     */
    fun resolveIso15693TagType(uidHex: String): String {
        val cleanUid = normalizeUid(uidHex)
        return when {
            cleanUid.length >= 4 && cleanUid.startsWith("E0") -> resolveIso15693UidTagType(cleanUid)
            cleanUid.length >= 4 && cleanUid.startsWith("E2") -> resolveEpcGen2TidTagType(cleanUid)
            else -> "ISO 15693 (NfcV)"
        }
    }

    /**
     * Erstellt eine vollständige [TransponderDetails]-Instanz für eine UID/TID.
     */
    fun parseTransponderDetails(uidHex: String): TransponderDetails {
        val clean = normalizeUid(uidHex)
        val idType = resolveIdentifierType(clean)
        val epcTid = if (idType == TagIdentifierType.EPC_GEN2_TID) parseEpcTidDetails(clean) else null
        val iso15693 = if (idType == TagIdentifierType.ISO15693_UID) parseIso15693Details(clean) else null
        val summary = resolveIso15693TagType(clean)

        return TransponderDetails(
            identifierType = idType,
            epcTid = epcTid,
            iso15693 = iso15693,
            formattedSummary = summary
        )
    }

    /**
     * Extrahiert die strukturierten TID-Informationen (Allocation Class, Flags, MDID, TMN, Chiphersteller, Modell, Seriennummer)
     * aus einer EPC Gen 2 / ISO 15963 TID-Zeichenkette nach GS1 TDS 2.0.
     */
    fun parseEpcTidDetails(tidHex: String): EpcTidDetails? {
        val cleanTid = tidHex.replace(":", "").replace(" ", "").trim().uppercase()
        if (!cleanTid.startsWith("E2") || cleanTid.length < 2) return null

        val allocationClass = if (cleanTid.length >= 2) cleanTid.substring(0, 2) else "E2"
        val headerHex = if (cleanTid.length >= 5) cleanTid.substring(2, 5) else cleanTid.substring(2)
        val headerVal = headerHex.toIntOrNull(16) ?: 0

        // Bit 11: XTID, Bit 10: Security, Bit 9: FileOpen
        val hasXtid = (headerVal and 0x800) != 0
        val hasSecurity = (headerVal and 0x400) != 0
        val hasFileOpen = (headerVal and 0x200) != 0

        // Bits 8..0: 9-Bit Mask Designer ID (MDID)
        val mdidInt = headerVal and 0x1FF
        val mdidHex = String.format("%03X", mdidInt)
        val mdidBinary = String.format("%9s", Integer.toBinaryString(mdidInt)).replace(' ', '0')

        val designer = maskDesigners.firstOrNull { it.mdidInt == mdidInt }
        val manufacturerName = designer?.manufacturer ?: if (mdidInt > 0) "MDID $mdidInt (0x$mdidHex)" else "Unbekannt"
        val manufacturerUrl = designer?.manufacturerUrl

        // Bits 11..0 in Zeichen 5..7: 12-Bit Tag Model Number (TMN)
        val tmnHex = if (cleanTid.length >= 8) cleanTid.substring(5, 8) else if (cleanTid.length > 5) cleanTid.substring(5) else ""
        val chip = if (tmnHex.isNotEmpty()) {
            designer?.chips?.firstOrNull { it.tmnHex.equals(tmnHex, ignoreCase = true) }
        } else null
        val modelName = chip?.modelName
        val productUrl = chip?.productUrl

        // Extended TID / Seriennummer ab Byte 4 (Zeichen 8)
        val serialNumberHex = if (cleanTid.length > 8) cleanTid.substring(8) else null

        return EpcTidDetails(
            allocationClass = allocationClass,
            hasXtid = hasXtid,
            hasSecurity = hasSecurity,
            hasFileOpen = hasFileOpen,
            mdidInt = mdidInt,
            mdidHex = mdidHex,
            mdidBinary = mdidBinary,
            manufacturerName = manufacturerName,
            manufacturerUrl = manufacturerUrl,
            tmnHex = tmnHex,
            modelName = modelName,
            productUrl = productUrl,
            serialNumberHex = serialNumberHex
        )
    }

    /**
     * Extrahiert die strukturierten UID-Informationen (Herstellercode, Herstellername, Produktcode, Modell, Seriennummer)
     * aus einer 64-Bit ISO 15693 (NfcV) UID.
     */
    fun parseIso15693Details(uidHex: String): Iso15693Details? {
        val cleanUid = normalizeUid(uidHex)
        if (!cleanUid.startsWith("E0") || cleanUid.length < 4) return null

        val mfgCodeHex = cleanUid.substring(2, 4)
        val productCodeHex = if (cleanUid.length >= 6) cleanUid.substring(4, 6) else ""
        val serialNumberHex = if (cleanUid.length > 6) cleanUid.substring(6) else ""

        var manufacturerName = when (mfgCodeHex) {
            "04" -> "NXP Semiconductors"
            "07" -> "Texas Instruments"
            "02" -> "STMicroelectronics"
            "16" -> "EM Microelectronic"
            "05" -> "Infineon Technologies"
            "2B", "08" -> "Fujitsu"
            "1D" -> "Maxim Integrated"
            "06" -> "Sony"
            else -> null
        }

        if (manufacturerName == null) {
            val mfgInt = mfgCodeHex.toIntOrNull(16)
            if (mfgInt != null) {
                val designer = maskDesigners.firstOrNull { it.mdidInt == mfgInt }
                if (designer != null && designer.manufacturer.isNotBlank()) {
                    manufacturerName = designer.manufacturer
                }
            }
        }
        if (manufacturerName == null) {
            manufacturerName = "Mfg: $mfgCodeHex"
        }

        var modelName: String? = null
        if (mfgCodeHex == "04") {
            modelName = when (productCodeHex) {
                "01" -> "ICODE SLIX"
                "02" -> "ICODE SLIX-S"
                "03" -> "ICODE SLIX2"
                "04" -> "ICODE DNA"
                "05" -> "ICODE SLIX-L"
                "00" -> "ICODE 1"
                else -> if (productCodeHex.isNotEmpty()) "ICODE (Prod: $productCodeHex)" else "ICODE"
            }
        } else if (mfgCodeHex == "07") {
            modelName = "Tag-it HF-I Plus"
        }

        return Iso15693Details(
            mfgCodeHex = mfgCodeHex,
            manufacturerName = manufacturerName,
            productCodeHex = productCodeHex,
            modelName = modelName,
            serialNumberHex = serialNumberHex
        )
    }

    /**
     * Löst eine 64-Bit ISO/IEC 15693 HF-Transponder-UID (Klasse E0h) auf.
     *
     * Aufbau nach ISO/IEC 15693-3 / ISO/IEC 15963 (von MSB nach LSB):
     * - Byte 7 (MSB): 0xE0 (ISO 15693 Kennung)
     * - Byte 6: 8-Bit IC-Herstellercode (nach ISO/IEC 7816-6, z. B. 0x04 = NXP, 0x07 = TI, 0x02 = ST)
     * - Byte 5: 8-Bit Produkttyp / Chipmodell des Herstellers (z. B. 0x01 = ICODE SLIX)
     * - Bytes 4..0: 40-Bit eindeutige Seriennummer
     */
    private fun resolveIso15693UidTagType(cleanUid: String): String {
        val mfgCode = cleanUid.substring(2, 4)
        val productCode = if (cleanUid.length >= 6) cleanUid.substring(4, 6) else ""
        when (mfgCode) {
            "04" -> {
                // NXP Semiconductors
                return when (productCode) {
                    "01" -> "NXP ICODE SLIX (ISO 15693)"
                    "02" -> "NXP ICODE SLIX-S (ISO 15693)"
                    "03" -> "NXP ICODE SLIX2 (ISO 15693)"
                    "04" -> "NXP ICODE DNA (ISO 15693)"
                    "05" -> "NXP ICODE SLIX-L (ISO 15693)"
                    "00" -> "NXP ICODE 1 (ISO 15693)"
                    else -> "NXP ICODE (ISO 15693)"
                }
            }
            "07" -> return "TI Tag-it HF-I Plus (ISO 15693)"
            "02" -> return "STMicroelectronics (ISO 15693)"
            "16" -> return "EM Microelectronic (ISO 15693)"
            "05" -> return "Infineon my-d (ISO 15693)"
            "2B", "08" -> return "Fujitsu FRAM (ISO 15693)"
            "1D" -> return "Maxim Integrated (ISO 15693)"
            "06" -> return "Sony (ISO 15693)"
        }

        // Check if mfgCode matches an entry in mdid_list.json
        val mfgInt = mfgCode.toIntOrNull(16)
        if (mfgInt != null) {
            val designer = maskDesigners.firstOrNull { it.mdidInt == mfgInt }
            if (designer != null && designer.manufacturer.isNotBlank()) {
                return "${designer.manufacturer} (ISO 15693)"
            }
        }

        return if (productCode.isNotEmpty()) {
            "ISO 15693 (NfcV, Mfg: $mfgCode, Prod: $productCode)"
        } else {
            "ISO 15693 (NfcV, Mfg: $mfgCode)"
        }
    }

    /**
     * Löst eine GS1 EPC Gen 2 / ISO 18000-6C TID-Struktur (Klasse E2h) auf.
     *
     * Aufbau eines 32-Bit Short TID nach GS1 TDS 2.0 (Bits 31..0):
     * - Bits 31..24 (Byte 0): 0xE2 (ISO/IEC 15963 Class Identifier für GS1 / EPC Gen 2)
     * - Bit 23: XTID indicator (1 = Extended Tag Identification vorhanden)
     * - Bit 22: Security indicator (1 = Authenticate/Challenge unterstützt)
     * - Bit 21: FileOpen indicator (1 = FileOpen unterstützt)
     * - Bits 20..12: 9-Bit Mask Designer ID (MDID, Hersteller)
     * - Bits 11..0: 12-Bit Tag Model Number (TMN, Chipmodell) / XTID-Segment
     *
     * In Hex-Darstellung (z. B. "E2806995"):
     * - Zeichen 0..1: "E2"
     * - Zeichen 2..4: 3 Hex-Ziffern (12 Bit: XTID/Sec/File-Flags + 9-Bit MDID)
     *   -> `mdidVal and 0x1FF` extrahiert die exakten 9 Bit der MDID.
     * - Zeichen 5..7: 3 Hex-Ziffern (12-Bit TMN im Format der GS1 MDID-Registry `mdid_list.json`)
     */
    private fun resolveEpcGen2TidTagType(cleanTid: String): String {
        val mdidHex = if (cleanTid.length >= 5) cleanTid.substring(2, 5) else cleanTid.substring(2)
        val mdidVal = mdidHex.toIntOrNull(16)
        val tmnHex = if (cleanTid.length >= 8) cleanTid.substring(5, 8) else ""

        if (mdidVal != null) {
            // Die 9-Bit MDID liegt in den unteren 9 Bits des 12-Bit Feldes (Bit 20..12)
            val mdid9Bit = mdidVal and 0x1FF
            val designer = maskDesigners.firstOrNull { it.mdidInt == mdid9Bit }
            if (designer != null) {
                val chip = if (tmnHex.isNotEmpty()) {
                    designer.chips.firstOrNull { it.tmnHex.equals(tmnHex, ignoreCase = true) }
                } else null

                return when {
                    chip != null -> "${designer.manufacturer} ${chip.modelName} (EPC Gen2)"
                    tmnHex.isNotEmpty() -> "${designer.manufacturer} (EPC Gen2, TMN: $tmnHex)"
                    else -> "${designer.manufacturer} (EPC Gen2)"
                }
            } else {
                val mdidFormattedHex = String.format("%03X", mdid9Bit)
                return when {
                    tmnHex.isNotEmpty() -> "EPC Gen2 (MDID: $mdid9Bit / 0x$mdidFormattedHex, TMN: 0x$tmnHex)"
                    else -> "EPC Gen2 (MDID: $mdid9Bit / 0x$mdidFormattedHex)"
                }
            }
        }

        return when {
            tmnHex.isNotEmpty() -> "EPC Gen2 (Header: $mdidHex, TMN: 0x$tmnHex)"
            else -> "EPC Gen2 (Header: $mdidHex)"
        }
    }

    private fun parseAsciiIfPossible(bytes: ByteArray): String? {
        val filtered = bytes.filter { it in 32..126 || it == 10.toByte() || it == 13.toByte() }
        if (filtered.size > 2 && filtered.size >= bytes.size / 2) {
            return String(bytes, StandardCharsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
        }
        return null
    }

    private fun buildSummary(
        uid: String,
        text: String?,
        rawHex: String,
        libraryData: FinnishLibraryData? = null
    ): String {
        return buildString {
            append("UID: ").append(uid)
            if (libraryData != null && !libraryData.isTagEmpty) {
                append(" | Item-ID: ").append(libraryData.itemId.ifBlank { "(keine)" })
                if (libraryData.country.isNotBlank() || libraryData.isil.isNotBlank()) {
                    val isilCode = listOfNotNull(libraryData.country.takeIf { it.isNotBlank() }, libraryData.isil.takeIf { it.isNotBlank() })
                        .joinToString("-")
                    append(" | ISIL: ").append(isilCode)
                }
                append(" | Teil: ").append(libraryData.partNo).append("/").append(libraryData.parts)
                append(" | Typ: ").append(libraryData.usageType)
                if (libraryData.afi.isNotBlank()) {
                    val statusDesc = when (libraryData.afi.uppercase()) {
                        "C2" -> "Ausgeliehen"
                        "07" -> "Gesichert"
                        else -> libraryData.afi
                    }
                    append(" | AFI: ").append(libraryData.afi.uppercase()).append(" (").append(statusDesc).append(")")
                }
                append(" | Ver: ").append(libraryData.version)
                append(" | CRC: ").append(if (libraryData.isCrcValid) "OK" else "Fehler")
            } else if (!text.isNullOrBlank()) {
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
