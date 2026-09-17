package com.example.nfcreader.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlBuilderTest {

    @Test
    fun testBuildUrlWithTemplatePlaceholders() {
        val template = "https://example.com/scan?userText={text}&tagValue={nfc}&tagId={uid}"
        val userText = "Raum 101"
        val nfcContent = "ISO-15693-VALUE-42"
        val uid = "E004015099887766"

        val result = UrlBuilder.buildUrl(
            baseUrlOrTemplate = template,
            userText = userText,
            nfcContent = nfcContent,
            uid = uid
        )

        assertEquals(
            "https://example.com/scan?userText=Raum+101&tagValue=ISO-15693-VALUE-42&tagId=E004015099887766",
            result
        )
    }

    @Test
    fun testBuildUrlWithJwtPlaceholder() {
        val template = "https://example.com/webhook?user={text}&jwt={jwt}"
        val result = UrlBuilder.buildUrl(
            baseUrlOrTemplate = template,
            userText = "User1",
            nfcContent = "NFC1",
            uid = "UID1",
            jwtToken = "eyJhbGciOi..."
        )
        assertEquals(
            "https://example.com/webhook?user=User1&jwt=eyJhbGciOi...",
            result
        )
    }

    @Test
    fun testBuildUrlWithNoPlaceholdersAppendsQueryParams() {
        val baseUrl = "https://api.my-server.de/nfc-endpoint"
        val userText = "Test äöü & ? = #"
        val nfcContent = "01020304"
        val uid = "E004015012345678"

        val result = UrlBuilder.buildUrl(
            baseUrlOrTemplate = baseUrl,
            userText = userText,
            nfcContent = nfcContent,
            uid = uid,
            timestamp = 1600000000000L
        )

        assertTrue(result.startsWith("https://api.my-server.de/nfc-endpoint?text="))
        assertTrue(result.contains("nfc=01020304"))
        assertTrue(result.contains("uid=E004015012345678"))
        assertTrue(result.contains("ts=1600000000000"))
    }

    @Test
    fun testBuildUrlWithExistingQueryParams() {
        val baseUrl = "https://api.my-server.de/endpoint?apikey=secret123"
        val userText = "Test"
        val nfcContent = "DATA"
        val uid = "UID1"

        val result = UrlBuilder.buildUrl(
            baseUrlOrTemplate = baseUrl,
            userText = userText,
            nfcContent = nfcContent,
            uid = uid,
            timestamp = 1000L
        )

        assertEquals(
            "https://api.my-server.de/endpoint?apikey=secret123&text=Test&nfc=DATA&uid=UID1&ts=1000",
            result
        )
    }

    @Test
    fun testEmptyUrlReturnsEmpty() {
        val result = UrlBuilder.buildUrl(
            baseUrlOrTemplate = "   ",
            userText = "Test",
            nfcContent = "DATA",
            uid = "UID"
        )
        assertEquals("", result)
    }
}
