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
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlBuilderTest {

    @Test
    fun testBuildUrlWithTemplatePlaceholders() {
        val template = "https://example.com/scan?userText={text}&rawHex={raw}&tagId={uid}"
        val userText = "Raum 101"
        val rawHex = "0100010133303131"
        val uid = "E004015099887766"

        val result = UrlBuilder.buildUrl(
            baseUrlOrTemplate = template,
            userText = userText,
            rawPayloadHex = rawHex,
            uid = uid
        )

        assertEquals(
            "https://example.com/scan?userText=Raum+101&rawHex=0100010133303131&tagId=E004015099887766",
            result
        )
    }

    @Test
    fun testBuildUrlWithJwtPlaceholder() {
        val template = "https://example.com/webhook?user={text}&jwt={jwt}&raw={raw}"
        val result = UrlBuilder.buildUrl(
            baseUrlOrTemplate = template,
            userText = "User1",
            rawPayloadHex = "AABBCC",
            uid = "UID1",
            jwtToken = "eyJhbGciOi..."
        )
        assertEquals(
            "https://example.com/webhook?user=User1&jwt=eyJhbGciOi...&raw=AABBCC",
            result
        )
    }

    @Test
    fun testBuildUrlWithNoPlaceholdersAppendsQueryParams() {
        val baseUrl = "https://api.my-server.de/nfc-endpoint"
        val userText = "Test äöü & ? = #"
        val rawHex = "01020304"
        val uid = "E004015012345678"

        val result = UrlBuilder.buildUrl(
            baseUrlOrTemplate = baseUrl,
            userText = userText,
            rawPayloadHex = rawHex,
            uid = uid,
            timestamp = 1600000000000L
        )

        assertTrue(result.startsWith("https://api.my-server.de/nfc-endpoint?text="))
        assertTrue(result.contains("raw=01020304"))
        assertTrue(result.contains("uid=E004015012345678"))
        assertTrue(result.contains("ts=1600000000000"))
    }

    @Test
    fun testBuildUrlWithExistingQueryParams() {
        val baseUrl = "https://api.my-server.de/endpoint?apikey=secret123"
        val userText = "Test"
        val rawHex = "DATA"
        val uid = "UID1"

        val result = UrlBuilder.buildUrl(
            baseUrlOrTemplate = baseUrl,
            userText = userText,
            rawPayloadHex = rawHex,
            uid = uid,
            timestamp = 1000L
        )

        assertEquals(
            "https://api.my-server.de/endpoint?apikey=secret123&text=Test&raw=DATA&uid=UID1&ts=1000",
            result
        )
    }

    @Test
    fun testEmptyUrlReturnsEmpty() {
        val result = UrlBuilder.buildUrl(
            baseUrlOrTemplate = "   ",
            userText = "Test",
            rawPayloadHex = "DATA",
            uid = "UID"
        )
        assertEquals("", result)
    }

    @Test
    fun testBuildUrlWithFinnishLibraryPlaceholders() {
        val template = "https://library.org/item?id={itemid}&isil={isil}&country={country}&part={partno}&total={parts}&type={usagetype}&v={version}&status={afi}&raw={raw}"
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
            userText = "Test",
            rawPayloadHex = "0100010133303131",
            uid = libraryData.uid,
            libraryData = libraryData
        )

        assertEquals(
            "https://library.org/item?id=3011000123&isil=ISIL-555&country=DE&part=1&total=3&type=1&v=1&status=07&raw=0100010133303131",
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
            userText = "Filiale",
            rawPayloadHex = "0102030405",
            uid = libraryData.uid,
            timestamp = 1700000000000L,
            libraryData = libraryData
        )

        assertTrue(result.contains("afi=C7"))
        assertTrue(result.contains("itemid=3011000123"))
        assertTrue(result.contains("raw=0102030405"))
    }
}
