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

package ch.infoage.nfcreader.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UrlBuilderTest {

    @Test
    fun testBuildUrlWithTemplatePlaceholders() {
        val template = "https://example.com/scan?markerVal={marker}&sess={session}&rawHex={raw}&tagId={uid}"
        val marker = "Raum 101"
        val rawHex = "0100010133303131"
        val uid = "E004015099887766"
        val session = "Pixel7_20261003"

        val result = UrlBuilder.buildUrl(
            baseUrlOrTemplate = template,
            marker = marker,
            rawPayloadHex = rawHex,
            uid = uid,
            session = session
        )

        assertEquals(
            "https://example.com/scan?markerVal=Raum+101&sess=Pixel7_20261003&rawHex=0100010133303131&tagId=E004015099887766",
            result
        )
    }

    @Test
    fun testBuildUrlWithLegacyTextPlaceholder() {
        val template = "https://example.com/scan?userText={text}&tagId={uid}"
        val result = UrlBuilder.buildUrl(
            baseUrlOrTemplate = template,
            marker = "Raum 101",
            uid = "E004"
        )
        assertEquals("https://example.com/scan?userText=Raum+101&tagId=E004", result)
    }

    @Test
    fun testBuildUrlWithJwtPlaceholder() {
        val template = "https://example.com/webhook?marker={marker}&jwt={jwt}&raw={raw}"
        val result = UrlBuilder.buildUrl(
            baseUrlOrTemplate = template,
            marker = "User1",
            rawPayloadHex = "AABBCC",
            uid = "UID1",
            jwtToken = "eyJhbGciOi..."
        )
        assertEquals(
            "https://example.com/webhook?marker=User1&jwt=eyJhbGciOi...&raw=AABBCC",
            result
        )
    }

    @Test
    fun testBuildUrlWithNoPlaceholdersAppendsQueryParams() {
        val baseUrl = "https://api.my-server.de/nfc-endpoint"
        val marker = "Test äöü & ? = #"
        val rawHex = "01020304"
        val uid = "E004015012345678"
        val timestamp = 1600000000000L
        val session = "DeviceA_20261003"

        val result = UrlBuilder.buildUrl(
            baseUrlOrTemplate = baseUrl,
            marker = marker,
            rawPayloadHex = rawHex,
            uid = uid,
            timestamp = timestamp,
            session = session
        )

        assertTrue(result.startsWith("https://api.my-server.de/nfc-endpoint?marker="))
        assertTrue(result.contains("session=DeviceA_20261003"))
        assertTrue(result.contains("raw=01020304"))
        assertTrue(result.contains("uid=E004015012345678"))
        assertTrue(result.contains("ts=1600000000000"))
        assertFalse(result.contains("text="))
    }

    @Test
    fun testBuildUrlForPostDoesNotContainGetParams() {
        val baseUrl = "https://api.my-server.de/nfc-endpoint?existing=param"
        val marker = "Test"
        val rawHex = "01020304"
        val uid = "E004015012345678"

        val result = UrlBuilder.buildUrl(
            baseUrlOrTemplate = baseUrl,
            marker = marker,
            rawPayloadHex = rawHex,
            uid = uid,
            httpMethod = "POST"
        )

        assertEquals("https://api.my-server.de/nfc-endpoint", result)
        assertFalse(result.contains("?"))
        assertFalse(result.contains("marker="))
        assertFalse(result.contains("existing="))
    }

    @Test
    fun testGenerateSessionFormat() {
        val timestamp = 1791038400000L // 2026-10-03 approx
        val expectedDate = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(timestamp))
        val sessionWithDevice = UrlBuilder.generateSession(deviceName = "Pixel 8", timestamp = timestamp)
        assertEquals("Pixel 8_$expectedDate", sessionWithDevice)

        val sessionWithoutDevice = UrlBuilder.generateSession(deviceName = "", timestamp = timestamp)
        assertEquals(expectedDate, sessionWithoutDevice)
    }

    @Test
    fun testBuildUrlWithExistingQueryParams() {
        val baseUrl = "https://api.my-server.de/endpoint?apikey=secret123"
        val marker = "Test"
        val rawHex = "DATA"
        val uid = "UID1"

        val result = UrlBuilder.buildUrl(
            baseUrlOrTemplate = baseUrl,
            marker = marker,
            rawPayloadHex = rawHex,
            uid = uid,
            timestamp = 1000L,
            session = "Device_20261003"
        )

        assertEquals(
            "https://api.my-server.de/endpoint?apikey=secret123&marker=Test&session=Device_20261003&raw=DATA&uid=UID1&ts=1000",
            result
        )
    }

    @Test
    fun testEmptyUrlReturnsEmpty() {
        val result = UrlBuilder.buildUrl(
            baseUrlOrTemplate = "   ",
            marker = "Test",
            rawPayloadHex = "DATA",
            uid = "UID"
        )
        assertEquals("", result)
    }

    @Test
    fun testBuildUrlWithFinnishLibraryPlaceholders() {
        val template = "https://library.org/item?id={itemid}&isil={isil}&country={country}&part={partno}&total={parts}&type={usagetype}&v={version}&status={afi}&raw={raw}&marker={marker}&session={session}"
        val libraryData = ch.infoage.nfcreader.nfc.FinnishLibraryData(
            uid = "E004015099887766",
            version = 1,
            usageType = 1,
            parts = 3,
            partNo = 1,
            itemId = "3011000123",
            country = "DE",
            isil = "ISIL-555",
            isCrcValid = true,
            isTagEmpty = false,
            afi = "07"
        )

        val result = UrlBuilder.buildUrl(
            baseUrlOrTemplate = template,
            marker = "Test",
            rawPayloadHex = "0100010133303131",
            uid = libraryData.uid,
            session = "Reader1_20261003",
            libraryData = libraryData
        )

        assertEquals(
            "https://library.org/item?id=3011000123&isil=ISIL-555&country=DE&part=1&total=3&type=1&v=1&status=07&raw=0100010133303131&marker=Test&session=Reader1_20261003",
            result
        )
    }

    @Test
    fun testBuildUrlWithNoPlaceholdersIncludesAfiWhenPresent() {
        val baseUrl = "https://api.library.org/scan"
        val libraryData = ch.infoage.nfcreader.nfc.FinnishLibraryData(
            uid = "E004015099887766",
            version = 1,
            usageType = 1,
            parts = 1,
            partNo = 1,
            itemId = "3011000123",
            country = "CH",
            isil = "ISIL-1",
            isCrcValid = true,
            isTagEmpty = false,
            afi = "C7"
        )

        val result = UrlBuilder.buildUrl(
            baseUrlOrTemplate = baseUrl,
            marker = "Filiale",
            rawPayloadHex = "0102030405",
            uid = libraryData.uid,
            timestamp = 1700000000000L,
            session = "Reader2_20261003",
            libraryData = libraryData
        )

        assertTrue(result.contains("marker=Filiale"))
        assertTrue(result.contains("session=Reader2_20261003"))
        assertTrue(result.contains("afi=C7"))
        assertTrue(result.contains("itemid=3011000123"))
        assertTrue(result.contains("raw=0102030405"))
    }
}
