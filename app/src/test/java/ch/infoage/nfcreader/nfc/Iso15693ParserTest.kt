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
    fun testParseAfiFromSystemInfoWithDsfidAndAfiSecured() {
        // Response format:
        // Byte 0: 0x00 (success)
        // Byte 1: 0x03 (DSFID + AFI flags set)
        // Bytes 2..9: UID (8 bytes)
        // Byte 10: DSFID (0x01)
        // Byte 11: AFI (0x07 = Gesichert)
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
    fun testParseAfiFromSystemInfoWithoutDsfidLoaned() {
        // Byte 0: 0x00
        // Byte 1: 0x02 (only AFI flag set)
        // Bytes 2..9: UID
        // Byte 10: AFI (0xC2 = Ausgeliehen)
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

    @Test
    fun testResolveIso15693TagTypeNxpChips() {
        assertEquals("NXP ICODE SLIX (ISO 15693)", Iso15693Parser.resolveIso15693TagType("E004015012345678"))
        assertEquals("NXP ICODE SLIX-S (ISO 15693)", Iso15693Parser.resolveIso15693TagType("E004025012345678"))
        assertEquals("NXP ICODE SLIX2 (ISO 15693)", Iso15693Parser.resolveIso15693TagType("E004035012345678"))
        assertEquals("NXP ICODE DNA (ISO 15693)", Iso15693Parser.resolveIso15693TagType("E004045012345678"))
        assertEquals("NXP ICODE SLIX-L (ISO 15693)", Iso15693Parser.resolveIso15693TagType("E004055012345678"))
        assertEquals("NXP ICODE 1 (ISO 15693)", Iso15693Parser.resolveIso15693TagType("E004005012345678"))
        assertEquals("NXP ICODE (ISO 15693)", Iso15693Parser.resolveIso15693TagType("E004995012345678"))
    }

    @Test
    fun testResolveIso15693TagTypeOtherManufacturers() {
        assertEquals("TI Tag-it HF-I Plus (ISO 15693)", Iso15693Parser.resolveIso15693TagType("E00700123456789A"))
        assertEquals("STMicroelectronics (ISO 15693)", Iso15693Parser.resolveIso15693TagType("E00200123456789A"))
        assertEquals("EM Microelectronic (ISO 15693)", Iso15693Parser.resolveIso15693TagType("E01600123456789A"))
        assertEquals("Infineon my-d (ISO 15693)", Iso15693Parser.resolveIso15693TagType("E00500123456789A"))
        assertEquals("Fujitsu FRAM (ISO 15693)", Iso15693Parser.resolveIso15693TagType("E02B00123456789A"))
        assertEquals("Fujitsu FRAM (ISO 15693)", Iso15693Parser.resolveIso15693TagType("E00800123456789A"))
        assertEquals("Maxim Integrated (ISO 15693)", Iso15693Parser.resolveIso15693TagType("E01D00123456789A"))
        assertEquals("Sony (ISO 15693)", Iso15693Parser.resolveIso15693TagType("E00600123456789A"))
    }

    @Test
    fun testResolveIso15693TagTypeFromMdidList() {
        // Shanghai Fudan Microelectronics Group (mdid: 000100111 = 39 = 0x27)
        assertEquals("Shanghai Fudan Microelectronics Group (ISO 15693)", Iso15693Parser.resolveIso15693TagType("E02700123456789A"))
        // Honeywell (mdid: 000110001 = 49 = 0x31)
        assertEquals("Honeywell (ISO 15693)", Iso15693Parser.resolveIso15693TagType("E03100123456789A"))
        // PJSC Mikron (mdid: 000110100 = 52 = 0x34)
        assertEquals("PJSC Mikron (ISO 15693)", Iso15693Parser.resolveIso15693TagType("E03400123456789A"))
        // 3ALogics Inc. (mdid: 001000111 = 71 = 0x47)
        assertEquals("3ALogics Inc. (ISO 15693)", Iso15693Parser.resolveIso15693TagType("E04700123456789A"))
        // Silicon Craft Technology Public Company Limited (mdid: 001001001 = 73 = 0x49)
        assertEquals("Silicon Craft Technology Public Company Limited (ISO 15693)", Iso15693Parser.resolveIso15693TagType("E04900123456789A"))
    }

    @Test
    fun testResolveEpcGen2TagType() {
        // Impinj (MDID 1 = 0x801), Monza R6 (TMN 160)
        assertEquals("Impinj Monza R6 (EPC Gen2)", Iso15693Parser.resolveIso15693TagType("E280116012345678"))
        // NXP Semiconductors (MDID 6 = 0x806), UCODE 8 (TMN 894)
        assertEquals("NXP Semiconductors UCODE 8 (EPC Gen2)", Iso15693Parser.resolveIso15693TagType("E280689412345678"))
        // Alien Technology (MDID 3 = 0x803), Higgs 3 (TMN 412)
        assertEquals("Alien Technology Higgs 3 (EPC Gen2)", Iso15693Parser.resolveIso15693TagType("E280341212345678"))
        // EM Microelectronic (MDID 11 = 0x80B), EM4425 (TMN 110)
        assertEquals("EM Microelectronic EM4425 (EPC Gen2)", Iso15693Parser.resolveIso15693TagType("E280B11012345678"))
        // EM Microelectronic (MDID 11 = 0x80B), EM4423 (TMN 0A0)
        assertEquals("EM Microelectronic EM4423 (EPC Gen2)", Iso15693Parser.resolveIso15693TagType("E280B0A012345678"))
        // Honeywell (MDID 49 = 0x831), IT70 (TMN 000)
        assertEquals("Honeywell IT70 (EPC Gen2)", Iso15693Parser.resolveIso15693TagType("E283100012345678"))
        // ST Microelectronics (MDID 7 = 0x807), XRAG2 (TMN 240)
        assertEquals("ST Microelectronics XRAG2 (EPC Gen2)", Iso15693Parser.resolveIso15693TagType("E280724012345678"))

        // Known manufacturer, unknown chip
        assertEquals("Impinj (EPC Gen2, TMN: 999)", Iso15693Parser.resolveIso15693TagType("E280199912345678"))
        // Known manufacturer without chip TMN
        assertEquals("Impinj (EPC Gen2)", Iso15693Parser.resolveIso15693TagType("E2801"))

        // Unknown manufacturer in EPC
        assertEquals("EPC Gen2 (MDID: FFF, TMN: 999)", Iso15693Parser.resolveIso15693TagType("E2FFF99912345678"))
        assertEquals("EPC Gen2 (MDID: FFF)", Iso15693Parser.resolveIso15693TagType("E2FFF"))
    }

    @Test
    fun testResolveIdentifierType() {
        assertEquals(TagIdentifierType.ISO15693_UID, Iso15693Parser.resolveIdentifierType("E004015012345678"))
        assertEquals(TagIdentifierType.ISO15693_UID, Iso15693Parser.resolveIdentifierType("E0:04:01:50:12:34:56:78"))
        assertEquals(TagIdentifierType.ISO15693_UID, Iso15693Parser.resolveIdentifierType("e004015012345678"))
        assertEquals(TagIdentifierType.EPC_GEN2_TID, Iso15693Parser.resolveIdentifierType("E2806995"))
        assertEquals(TagIdentifierType.EPC_GEN2_TID, Iso15693Parser.resolveIdentifierType("E28069952000500101D589CC"))
        assertEquals(TagIdentifierType.EPC_GEN2_TID, Iso15693Parser.resolveIdentifierType("E2:80:69:95"))
        assertEquals(TagIdentifierType.GENERIC, Iso15693Parser.resolveIdentifierType("04A1B2C3"))
        assertEquals(TagIdentifierType.GENERIC, Iso15693Parser.resolveIdentifierType(""))
    }

    @Test
    fun testResolveEpcGen2ShortAndExtendedTidFromSpec() {
        // NXP Semiconductors UCODE 9 (Short TID: E2806995)
        assertEquals("NXP Semiconductors UCODE 9 (EPC Gen2)", Iso15693Parser.resolveIso15693TagType("E2806995"))
        // NXP Semiconductors UCODE 9 (Extended TID / XTID: E28069952000500101D589CC)
        assertEquals("NXP Semiconductors UCODE 9 (EPC Gen2)", Iso15693Parser.resolveIso15693TagType("E28069952000500101D589CC"))
        // Formatted with colons
        assertEquals("NXP Semiconductors UCODE 9 (EPC Gen2)", Iso15693Parser.resolveIso15693TagType("E2:80:69:95:20:00:50:01"))
        // Impinj Monza R6-P (MDID 1 = 0x801, TMN 170)
        assertEquals("Impinj Monza R6-P (EPC Gen2)", Iso15693Parser.resolveIso15693TagType("E2801170"))
        // Alien Technology Higgs 4 (MDID 3 = 0x803, TMN 414)
        assertEquals("Alien Technology Higgs 4 (EPC Gen2)", Iso15693Parser.resolveIso15693TagType("E2803414"))
    }

    @Test
    fun testResolveIso15693WithFormattedUid() {
        assertEquals("NXP ICODE SLIX (ISO 15693)", Iso15693Parser.resolveIso15693TagType("E0:04:01:50:12:34:56:78"))
        assertEquals("NXP ICODE SLIX2 (ISO 15693)", Iso15693Parser.resolveIso15693TagType("E0 04 03 50 12 34 56 78"))
    }

    @Test
    fun testParseEpcTidDetailsShortTid() {
        val details = Iso15693Parser.parseEpcTidDetails("E2806995")
        org.junit.Assert.assertNotNull(details)
        details?.let {
            assertEquals("E2", it.allocationClass)
            assertEquals(true, it.hasXtid)
            assertEquals(false, it.hasSecurity)
            assertEquals(false, it.hasFileOpen)
            assertEquals(6, it.mdidInt)
            assertEquals("006", it.mdidHex)
            assertEquals("000000110", it.mdidBinary)
            assertEquals("NXP Semiconductors", it.manufacturerName)
            assertEquals("http://www.nxp.com/#/homepage", it.manufacturerUrl)
            assertEquals("995", it.tmnHex)
            assertEquals("UCODE 9", it.modelName)
            assertEquals(null, it.serialNumberHex)
        }
    }

    @Test
    fun testParseEpcTidDetailsExtendedTid() {
        val details = Iso15693Parser.parseEpcTidDetails("E28069952000500101D589CC")
        org.junit.Assert.assertNotNull(details)
        details?.let {
            assertEquals("E2", it.allocationClass)
            assertEquals(true, it.hasXtid)
            assertEquals(6, it.mdidInt)
            assertEquals("NXP Semiconductors", it.manufacturerName)
            assertEquals("995", it.tmnHex)
            assertEquals("UCODE 9", it.modelName)
            assertEquals("2000500101D589CC", it.serialNumberHex)
        }
    }

    @Test
    fun testParseEpcTidDetailsVariousManufacturers() {
        // Impinj Monza R6 (MDID 1 = 0x801, TMN 160)
        val monza = Iso15693Parser.parseEpcTidDetails("E2801160")
        org.junit.Assert.assertNotNull(monza)
        assertEquals(1, monza?.mdidInt)
        assertEquals("Impinj", monza?.manufacturerName)
        assertEquals("Monza R6", monza?.modelName)

        // Alien Higgs 4 (MDID 3 = 0x803, TMN 414)
        val higgs = Iso15693Parser.parseEpcTidDetails("E28034142000200080004140")
        org.junit.Assert.assertNotNull(higgs)
        assertEquals(3, higgs?.mdidInt)
        assertEquals("Alien Technology", higgs?.manufacturerName)
        assertEquals("Higgs 4", higgs?.modelName)
        assertEquals("2000200080004140", higgs?.serialNumberHex)

        // EM Microelectronic EM4425 (MDID 11 = 0x80B, TMN 110)
        val em = Iso15693Parser.parseEpcTidDetails("E280B110")
        org.junit.Assert.assertNotNull(em)
        assertEquals(11, em?.mdidInt)
        assertEquals("EM Microelectronic", em?.manufacturerName)
        assertEquals("EM4425", em?.modelName)
    }

    @Test
    fun testParseIso15693Details() {
        val slix = Iso15693Parser.parseIso15693Details("E004015012345678")
        org.junit.Assert.assertNotNull(slix)
        assertEquals("04", slix?.mfgCodeHex)
        assertEquals("NXP Semiconductors", slix?.manufacturerName)
        assertEquals("01", slix?.productCodeHex)
        assertEquals("ICODE SLIX", slix?.modelName)
        assertEquals("5012345678", slix?.serialNumberHex)

        val ti = Iso15693Parser.parseIso15693Details("E00700123456789A")
        org.junit.Assert.assertNotNull(ti)
        assertEquals("07", ti?.mfgCodeHex)
        assertEquals("Texas Instruments", ti?.manufacturerName)
        assertEquals("Tag-it HF-I Plus", ti?.modelName)
    }

    @Test
    fun testParseTransponderDetails() {
        val epcDetails = Iso15693Parser.parseTransponderDetails("E28069952000500101D589CC")
        assertEquals(TagIdentifierType.EPC_GEN2_TID, epcDetails.identifierType)
        org.junit.Assert.assertNotNull(epcDetails.epcTid)
        org.junit.Assert.assertNull(epcDetails.iso15693)
        assertEquals("NXP Semiconductors UCODE 9 (EPC Gen2)", epcDetails.formattedSummary)

        val isoDetails = Iso15693Parser.parseTransponderDetails("E004015012345678")
        assertEquals(TagIdentifierType.ISO15693_UID, isoDetails.identifierType)
        org.junit.Assert.assertNull(isoDetails.epcTid)
        org.junit.Assert.assertNotNull(isoDetails.iso15693)
        assertEquals("NXP ICODE SLIX (ISO 15693)", isoDetails.formattedSummary)
    }

    @Test
    fun testMaskDesignersLoaded() {
        assertEquals(83, Iso15693Parser.maskDesigners.size)
    }

    @Test
    fun testResolveIso15693TagTypeFallback() {
        assertEquals("ISO 15693 (NfcV, Mfg: FF, Prod: 00)", Iso15693Parser.resolveIso15693TagType("E0FF00123456789A"))
        assertEquals("ISO 15693 (NfcV, Mfg: FF)", Iso15693Parser.resolveIso15693TagType("E0FF"))
        assertEquals("ISO 15693 (NfcV)", Iso15693Parser.resolveIso15693TagType("04A1B2C3"))
        assertEquals("ISO 15693 (NfcV)", Iso15693Parser.resolveIso15693TagType(""))
    }
}
