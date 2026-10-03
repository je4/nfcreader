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

package ch.infoage.nfcreader.ui

import ch.infoage.nfcreader.data.network.JwtGenerator
import ch.infoage.nfcreader.data.network.UrlDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NfcViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockWebServer: MockWebServer
    private lateinit var viewModel: NfcViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockWebServer = MockWebServer()
        mockWebServer.start()

        val urlDispatcher = UrlDispatcher(dispatcher = testDispatcher)
        viewModel = NfcViewModel(urlDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        mockWebServer.shutdown()
    }

    @Test
    fun testUserTextAndUrlConfiguration() {
        viewModel.setUserText("Mitarbeiter 42")
        assertEquals("Mitarbeiter 42", viewModel.userText.value)

        viewModel.setTargetUrl("https://my-domain.org/nfc")
        assertEquals("https://my-domain.org/nfc", viewModel.targetUrl.value)

        viewModel.setHttpMethod("POST")
        assertEquals("POST", viewModel.httpMethod.value)

        viewModel.setJwtKey("test.jwt.key")
        assertEquals("test.jwt.key", viewModel.jwtKey.value)

        viewModel.setDebugMode(true)
        assertEquals(true, viewModel.debugMode.value)

        viewModel.setScanningActive(false)
        assertEquals(false, viewModel.isScanningActive.value)
    }

    @Test
    fun testProcessScanSuccess() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{\"success\":true}")
        )

        val serverUrl = mockWebServer.url("/scan-webhook").toString()
        viewModel.setUserText("Kiste A-9")
        viewModel.setTargetUrl(serverUrl)
        viewModel.setJwtKey("jwt-secret-abc")

        viewModel.processScan(
            uid = "E004015099887766",
            tagType = "ISO 15693 (NfcV)",
            content = "PAYLOAD-BLOCK-DATA",
            rawPayloadHex = "01020304"
        )

        advanceUntilIdle()

        val recorded = mockWebServer.takeRequest()
        val authHeader = recorded.getHeader("Authorization")
        assertNotNull(authHeader)
        assertTrue(authHeader!!.startsWith("Bearer "))
        val token = authHeader.removePrefix("Bearer ")
        assertTrue(JwtGenerator.verifySignature(token, "jwt-secret-abc"))

        val payload = JwtGenerator.parsePayload(token)
        assertNotNull(payload)
        val iat = payload!!.getLong("iat")
        val exp = payload.getLong("exp")
        assertEquals(60L, exp - iat)

        val lastScan = viewModel.lastScan.value
        assertNotNull(lastScan)
        assertEquals("E004015099887766", lastScan?.uid)
        assertEquals("Kiste A-9", lastScan?.location)
        assertEquals("PAYLOAD-BLOCK-DATA", lastScan?.content)
        assertEquals("01020304", lastScan?.rawPayloadHex)
        assertEquals(200, lastScan?.httpStatus)
        assertTrue(lastScan?.isSuccess == true)
        assertNotNull(lastScan?.httpRequestDebug)
        assertTrue(lastScan?.httpRequestDebug?.contains("URL: ") == true)

        val history = viewModel.scanHistory.value
        assertEquals(1, history.size)
        assertEquals(lastScan, history[0])

        viewModel.clearHistory()
        assertTrue(viewModel.scanHistory.value.isEmpty())
        assertEquals(null, viewModel.lastScan.value)
    }

    @Test
    fun testProcessScanWithDebugModeAndPost() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{\"success\":true}")
        )

        val serverUrl = mockWebServer.url("/post-debug").toString()
        viewModel.setTargetUrl(serverUrl)
        viewModel.setHttpMethod("POST")
        viewModel.setUserText("DebugUserText")
        viewModel.setJwtKey("secret123")
        viewModel.setDebugMode(true)

        viewModel.processScan(
            uid = "E004015099999999",
            tagType = "ISO 15693 (NfcV)",
            content = "HEX-DATA",
            rawPayloadHex = "AABBCCDD"
        )

        advanceUntilIdle()

        val lastScan = viewModel.lastScan.value
        assertNotNull(lastScan)
        assertEquals("E004015099999999", lastScan?.uid)
        assertEquals("POST", lastScan?.httpMethod)
        assertEquals("secret123", lastScan?.jwtKey)
        val authHeader = lastScan?.requestHeaders?.get("Authorization")
        assertNotNull(authHeader)
        assertTrue(authHeader!!.startsWith("Bearer eyJ"))
        val token = authHeader.removePrefix("Bearer ")
        assertTrue(JwtGenerator.verifySignature(token, "secret123"))

        assertTrue(lastScan.requestBody?.contains("\"marker\":\"DebugUserText\"") == true)
        assertTrue(lastScan.requestBody?.contains("\"raw\":\"AABBCCDD\"") == true)
        assertTrue(lastScan.httpRequestDebug?.contains("Method: POST") == true)
        assertTrue(lastScan.httpRequestDebug?.contains("Authorization: Bearer eyJ") == true)
        assertTrue(lastScan.httpRequestDebug?.contains("\"marker\":\"DebugUserText\"") == true)
    }
}
