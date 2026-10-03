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
            marker = "Scanner1",
            nfcContent = "TAG-VALUE-123",
            uid = "E004015011223344",
            httpMethod = "GET",
            session = "Device_20261003"
        )

        assertTrue(result.isSuccess)
        val response = result.getOrThrow()
        assertEquals(200, response.httpStatus)
        assertEquals("{\"status\":\"ok\"}", response.responseBody)

        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("GET", recordedRequest.method)
        assertTrue(recordedRequest.path?.contains("marker=Scanner1") == true)
        assertTrue(recordedRequest.path?.contains("session=Device_20261003") == true)
        assertTrue(recordedRequest.path?.contains("raw=TAG-VALUE-123") == true)
    }

    @Test
    fun testPostDispatchSuccess() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setBody("{\"created\":true}")
        )

        val baseUrl = mockWebServer.url("/api/post-scan?ignore=me").toString()
        val result = urlDispatcher.dispatchScan(
            targetUrlTemplate = baseUrl,
            marker = "ScannerPost",
            rawPayloadHex = "ISO15693-VALUE",
            uid = "E00401509988",
            httpMethod = "POST",
            session = "Handheld_20261003"
        )

        assertTrue(result.isSuccess)
        val response = result.getOrThrow()
        assertEquals(201, response.httpStatus)

        val recordedRequest = mockWebServer.takeRequest()
        assertEquals("POST", recordedRequest.method)
        // Beim POST Aufruf sollen keine GET-Parameter vorhanden sein
        assertEquals("/api/post-scan", recordedRequest.path)
        val bodyText = recordedRequest.body.readUtf8()
        assertTrue(bodyText.contains("\"marker\":\"ScannerPost\""))
        assertTrue(bodyText.contains("\"session\":\"Handheld_20261003\""))
        assertTrue(bodyText.contains("\"raw\":\"ISO15693-VALUE\""))
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

    @Test
    fun testScanResponseContainsHttpRequestDebugDetails() = runBlocking {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{\"ok\":true}")
        )

        val baseUrl = mockWebServer.url("/debug-test").toString()
        val result = urlDispatcher.dispatchScan(
            targetUrlTemplate = baseUrl,
            marker = "DebugMarker",
            nfcContent = "DEBUG-NFC",
            uid = "E00401501234",
            httpMethod = "POST",
            jwtToken = "debug-jwt-token",
            jwtKey = "test-secret-key"
        )

        assertTrue(result.isSuccess)
        val response = result.getOrThrow()
        assertTrue(response.httpRequestDebug.contains("URL: "))
        assertTrue(response.httpRequestDebug.contains("Method: POST"))
        assertTrue(response.httpRequestDebug.contains("Authorization: Bearer debug-jwt-token"))
        assertTrue(response.httpRequestDebug.contains("\"marker\":\"DebugMarker\""))
        assertTrue(response.requestHeaders.containsKey("User-Agent"))
        assertEquals("Bearer debug-jwt-token", response.requestHeaders["Authorization"])
        assertEquals("test-secret-key", response.jwtKey)
        assertTrue(response.requestBody?.contains("\"uid\":\"E00401501234\"") == true)
    }

    @Test
    fun testPrepareRequestForGetAndPost() {
        val preparedGet = urlDispatcher.prepareRequest(
            targetUrlTemplate = "https://example.com/api",
            marker = "GetMarker",
            uid = "E004UID",
            httpMethod = "GET",
            jwtToken = "token123",
            jwtKey = "secret-jwt-xyz"
        )
        assertEquals("GET", preparedGet.httpMethod)
        assertTrue(preparedGet.fullUrl.contains("https://example.com/api"))
        assertEquals("Bearer token123", preparedGet.headers["Authorization"])
        assertEquals(null, preparedGet.body)
        assertTrue(preparedGet.debugString.contains("Method: GET"))
        assertTrue(preparedGet.debugString.contains("Authorization: Bearer token123"))
        assertTrue(preparedGet.debugString.contains("Body: (kein Body)"))

        val preparedPost = urlDispatcher.prepareRequest(
            targetUrlTemplate = "https://example.com/post-api",
            marker = "PostMarker",
            uid = "E004UID2",
            httpMethod = "POST"
        )
        assertEquals("POST", preparedPost.httpMethod)
        assertTrue(preparedPost.body?.contains("\"marker\":\"PostMarker\"") == true)
        assertTrue(preparedPost.debugString.contains("Method: POST"))
        assertTrue(preparedPost.debugString.contains("\"marker\":\"PostMarker\""))
    }
}
