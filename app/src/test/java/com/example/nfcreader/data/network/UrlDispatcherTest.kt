package com.example.nfcreader.data.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UrlDispatcherTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var urlDispatcher: UrlDispatcher

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        urlDispatcher = UrlDispatcher()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun testGetDispatchSuccess() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{\"status\":\"ok\"}")
        )

        val baseUrl = mockWebServer.url("/scan").toString()
        val result = urlDispatcher.dispatchScan(
            targetUrlTemplate = baseUrl,
            userText = "Scanner1",
            nfcContent = "TAG-VALUE-123",
            uid = "E004015011223344",
            httpMethod = "GET"
        )

        assertTrue(result.isSuccess)
        val response = result.getOrThrow()
        assertEquals(200, response.httpStatus)
        assertEquals("{\"status\":\"ok\"}", response.responseBody)

        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("GET", recordedRequest.method)
        assertTrue(recordedRequest.path?.contains("text=Scanner1") == true)
        assertTrue(recordedRequest.path?.contains("nfc=TAG-VALUE-123") == true)
    }

    @Test
    fun testPostDispatchSuccess() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody("{\"created\":true}")
        )

        val baseUrl = mockWebServer.url("/api/post-scan").toString()
        val result = urlDispatcher.dispatchScan(
            targetUrlTemplate = baseUrl,
            userText = "ScannerPost",
            nfcContent = "ISO15693-VALUE",
            uid = "E00401509988",
            httpMethod = "POST"
        )

        assertTrue(result.isSuccess)
        val response = result.getOrThrow()
        assertEquals(201, response.httpStatus)

        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("POST", recordedRequest.method)
        val bodyText = recordedRequest.body.readUtf8()
        assertTrue(bodyText.contains("\"text\":\"ScannerPost\""))
        assertTrue(bodyText.contains("\"nfc\":\"ISO15693-VALUE\""))
    }

    @Test
    fun testDispatchWithJwtTokenSetsAuthHeader() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{\"authorized\":true}")
        )

        val baseUrl = mockWebServer.url("/secure-scan").toString()
        val jwtKey = "sample.jwt.token.value"
        val result = urlDispatcher.dispatchScan(
            targetUrlTemplate = baseUrl,
            userText = "AuthTest",
            nfcContent = "SECURE-NFC",
            uid = "E004015099",
            httpMethod = "POST",
            jwtToken = jwtKey
        )

        assertTrue(result.isSuccess)
        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("Bearer sample.jwt.token.value", recordedRequest.getHeader("Authorization"))
        val bodyText = recordedRequest.body.readUtf8()
        assertTrue(bodyText.contains("\"jwt\":\"sample.jwt.token.value\""))
    }

    @Test
    fun testDispatchWithBearerPrefixedJwt() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{\"authorized\":true}")
        )

        val baseUrl = mockWebServer.url("/secure-get").toString()
        val jwtKey = "Bearer custom.jwt.token"
        val result = urlDispatcher.dispatchScan(
            targetUrlTemplate = baseUrl,
            userText = "GetAuth",
            nfcContent = "SECURE-NFC-GET",
            uid = "E004015088",
            httpMethod = "GET",
            jwtToken = jwtKey
        )

        assertTrue(result.isSuccess)
        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("Bearer custom.jwt.token", recordedRequest.getHeader("Authorization"))
    }

    @Test
    fun testHttpErrorStatus() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("Internal Server Error")
        )

        val baseUrl = mockWebServer.url("/fail").toString()
        val result = urlDispatcher.dispatchScan(
            targetUrlTemplate = baseUrl,
            userText = "Test",
            nfcContent = "TAG",
            uid = "UID",
            httpMethod = "GET"
        )

        assertTrue(result.isFailure)
    }
}
