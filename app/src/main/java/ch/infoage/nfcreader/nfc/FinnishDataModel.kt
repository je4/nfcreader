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

import java.nio.charset.StandardCharsets

/**
 * Repräsentiert die Datenstruktur eines RFID-Tags nach dem finnischen Bibliotheksdatenmodell
 * (Basis für ISO 28560-3).
 */
data class FinnishLibraryData(
    val uid: String,
    val version: Int,
    val usageType: Int,
    val parts: Int,
    val partNo: Int,
    val itemId: String,
    val country: String,
    val isil: String,
    val isCrcValid: Boolean,
    val isTagEmpty: Boolean,
    val crcHex: String = "",
    val afi: String = ""
) {
    /**
     * Erzeugt eine einzeilige, strukturierte Zusammenfassung der relevanten Felder.
     */
    fun toFormattedString(): String {
        if (isTagEmpty) return "Tag ist leer (nur Nullen)"
        return buildString {
            append("Item-ID: ").append(itemId.ifBlank { "(keine)" })
            if (country.isNotBlank() || isil.isNotBlank()) {
                val isilCode = listOfNotNull(country.takeIf { it.isNotBlank() }, isil.takeIf { it.isNotBlank() })
                    .joinToString("-")
                append(" | ISIL: ").append(isilCode)
            }
            append(" | Teil: ").append(partNo).append("/").append(parts)
            append(" | Typ: ").append(usageType)
            if (afi.isNotBlank()) {
                val statusDesc = when (afi.uppercase()) {
                    "C2" -> "Ausgeliehen"
                    "07" -> "Gesichert"
                    else -> afi
                }
                append(" | AFI: ").append(afi.uppercase()).append(" (").append(statusDesc).append(")")
            }
            append(" | Ver: ").append(version)
            append(" | CRC: ").append(if (isCrcValid) "OK" else "Fehler")
        }
    }
}

object FinnishDataModelParser {

    /**
     * Parst die Rohdaten eines ISO 15693 Tags nach dem finnischen Datenmodell.
     * Benötigt für ein vollständiges Datenmodell mindestens 3 Bytes Headerdaten.
     * Optional kann der ausgelesene AFI-Wert (Application Family Identifier) für den Ausleihstatus übergeben werden.
     */
    fun parse(uidHex: String, data: ByteArray, afi: String? = null): FinnishLibraryData? {
        if (data.isEmpty()) {
            return null
        }

        val isEmpty = data.all { it == 0.toByte() }

        // Byte 0: Version (obere 4 Bit) und UsageType (untere 4 Bit)
        val byte0 = data[0].toInt() and 0xFF
        val version = (byte0 ushr 4) and 0x0F
        val usageType = byte0 and 0x0F

        // Byte 1: Gesamtanzahl Teile im Set
        val parts = if (data.size > 1) data[1].toInt() and 0xFF else 0

        // Byte 2: Teilnummer dieses Mediums
        val partNo = if (data.size > 2) data[2].toInt() and 0xFF else 0

        // Byte 3..18 (16 Bytes): Primary Item ID / Barcode
        val rawItemId = when {
            data.size >= 19 -> String(data.copyOfRange(3, 19), StandardCharsets.UTF_8)
            data.size > 3 -> String(data.copyOfRange(3, data.size), StandardCharsets.UTF_8)
            else -> ""
        }.trim { it <= ' ' || it == '\u0000' }

        // Byte 19..20 (2 Bytes): CRC16-CCITT (LSB first)
        val (isCrcValid, crcHex) = if (data.size >= 21) {
            val calcCrc = calculateCrc(data)
            val expectedLsb = (calcCrc and 0xFF).toByte()
            val expectedMsb = ((calcCrc ushr 8) and 0xFF).toByte()
            val valid = (data[19] == expectedLsb && data[20] == expectedMsb)
            val hex = String.format("%02X%02X", data[19].toInt() and 0xFF, data[20].toInt() and 0xFF)
            Pair(valid, hex)
        } else {
            Pair(false, "")
        }

        // Byte 21..22 (2 Bytes): Ländercode der Bibliothek (z. B. DE, CH, FI)
        val country = when {
            data.size >= 23 -> String(data.copyOfRange(21, 23), StandardCharsets.UTF_8)
            data.size > 21 -> String(data.copyOfRange(21, data.size), StandardCharsets.UTF_8)
            else -> ""
        }.trim { it <= ' ' || it == '\u0000' }

        // Byte 23..34 (bis zu 12 Bytes): ISIL Bibliothekscode
        val isil = if (data.size > 23) {
            val isilEnd = minOf(data.size, 35)
            String(data.copyOfRange(23, isilEnd), StandardCharsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
        } else {
            ""
        }

        return FinnishLibraryData(
            uid = uidHex,
            version = version,
            usageType = usageType,
            parts = parts,
            partNo = partNo,
            itemId = rawItemId,
            country = country,
            isil = isil,
            isCrcValid = isCrcValid,
            isTagEmpty = isEmpty,
            crcHex = crcHex,
            afi = afi.orEmpty().trim().uppercase()
        )
    }

    /**
     * Erstellt einen Byte-Block aus den Modelldaten inkl. korrekter CRC16-Prüfsumme.
     */
    fun encode(model: FinnishLibraryData, size: Int = 36): ByteArray {
        val block = ByteArray(maxOf(size, 36))
        block[0] = (((model.version and 0x0F) shl 4) or (model.usageType and 0x0F)).toByte()
        block[1] = (model.parts and 0xFF).toByte()
        block[2] = (model.partNo and 0xFF).toByte()

        val itemIdBytes = model.itemId.toByteArray(StandardCharsets.UTF_8)
        for (i in 0 until minOf(itemIdBytes.size, 16)) {
            block[3 + i] = itemIdBytes[i]
        }

        val countryBytes = model.country.toByteArray(StandardCharsets.UTF_8)
        for (i in 0 until minOf(countryBytes.size, 2)) {
            block[21 + i] = countryBytes[i]
        }

        val isilBytes = model.isil.toByteArray(StandardCharsets.UTF_8)
        for (i in 0 until minOf(isilBytes.size, 12)) {
            block[23 + i] = isilBytes[i]
        }

        val crc = calculateCrc(block)
        block[19] = (crc and 0xFF).toByte()
        block[20] = ((crc ushr 8) and 0xFF).toByte()

        return block
    }

    /**
     * Berechnet die CRC16-CCITT Prüfsumme (Polynom 0x1021, Init 0xFFFF)
     * über die ersten 32 Bytes unter Auslassung der CRC-Bytes 19 und 20.
     */
    fun calculateCrc(data: ByteArray): Int {
        val crcData = ByteArray(maxOf(data.size, 32))
        val copyLen1 = minOf(data.size, 19)
        System.arraycopy(data, 0, crcData, 0, copyLen1)
        if (data.size > 21) {
            System.arraycopy(data, 21, crcData, 19, data.size - 21)
        }

        var crc = 0xFFFF
        val polynomial = 0x1021

        for (p in 0 until 32) {
            val byteVal = crcData[p].toInt() and 0xFF
            for (i in 0 until 8) {
                val bit = ((byteVal ushr (7 - i)) and 1) == 1
                val c15 = ((crc ushr 15) and 1) == 1
                crc = (crc shl 1) and 0xFFFF
                if (c15 xor bit) {
                    crc = crc xor polynomial
                }
            }
        }
        return crc and 0xFFFF
    }
}
