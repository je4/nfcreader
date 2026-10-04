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

        viewModel.setScanningActive(true)
        assertEquals(true, viewModel.isScanningActive.value)

        viewModel.setScanningActive(false)
        assertEquals(false, viewModel.isScanningActive.value)
    }

    @Test
    fun testScanningRequiresLocation() {
        // Initial state: userText is empty, scanning must be inactive
        assertEquals("", viewModel.userText.value)
        assertEquals(false, viewModel.isScanningActive.value)

        // Attempting to activate scan without location must fail
        viewModel.setScanningActive(true)
        assertEquals(false, viewModel.isScanningActive.value)

        // Setting a non-empty location enables scan activation
        viewModel.setUserText("Lager 1")
        assertEquals("Lager 1", viewModel.userText.value)
        viewModel.setScanningActive(true)
        assertEquals(true, viewModel.isScanningActive.value)

        // Clearing location must automatically deactivate scanning
        viewModel.setUserText("")
        assertEquals(false, viewModel.isScanningActive.value)

        // Attempting to re-activate without location remains inactive
        viewModel.setScanningActive(true)
        assertEquals(false, viewModel.isScanningActive.value)
    }

    @Test
    fun testTriggerTestScanRequiresLocation() = runTest {
        // With empty location, triggerTestScan must not perform a scan
        viewModel.setUserText("")
        viewModel.triggerTestScan()
        advanceUntilIdle()
        assertEquals(null, viewModel.lastScan.value)
        assertTrue(viewModel.scanHistory.value.isEmpty())

        // With location, triggerTestScan performs a scan
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("{\"success\":true}")
        )
        viewModel.setTargetUrl(mockWebServer.url("/test").toString())
        viewModel.setUserText("Raum 101")
        viewModel.triggerTestScan("Test Payload")
        advanceUntilIdle()
        assertNotNull(viewModel.lastScan.value)
        assertEquals(1, viewModel.scanHistory.value.size)
        assertEquals("Raum 101", viewModel.lastScan.value?.location)
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

    @Test
    fun testTabSwitching() {
        assertEquals(AppTab.SCAN, viewModel.currentTab.value)

        // Activate scan in SCAN tab
        viewModel.setUserText("Lager 1")
        viewModel.setScanningActive(true)
        assertTrue(viewModel.isScanningActive.value)

        // Switching to EDIT tab must stop the active scan
        viewModel.setTab(AppTab.EDIT)
        assertEquals(AppTab.EDIT, viewModel.currentTab.value)
        assertEquals(false, viewModel.isScanningActive.value)

        // Cannot activate scan while in EDIT tab
        viewModel.setScanningActive(true)
        assertEquals(false, viewModel.isScanningActive.value)

        // Switching back to SCAN tab
        viewModel.setTab(AppTab.SCAN)
        assertEquals(AppTab.SCAN, viewModel.currentTab.value)
        assertEquals(false, viewModel.isScanningActive.value)

        // Can activate scan again when back in SCAN tab
        viewModel.setScanningActive(true)
        assertEquals(true, viewModel.isScanningActive.value)
    }

    @Test
    fun testEditActionTriggers() {
        assertEquals(EditAction.NONE, viewModel.pendingEditAction.value)

        // Read trigger
        viewModel.onEditReadClicked()
        assertEquals(EditAction.READ, viewModel.pendingEditAction.value)
        assertEquals(EditStatusType.PENDING, viewModel.editStatusType.value)

        // Write trigger
        val dummyData = ch.infoage.nfcreader.nfc.FinnishLibraryData(
            uid = "E004015011223344",
            version = 1,
            usageType = 1,
            parts = 1,
            partNo = 1,
            itemId = "ITEM-001",
            country = "CH",
            isil = "ISIL-1",
            isCrcValid = true,
            isTagEmpty = false,
            afi = "C7"
        )
        viewModel.onEditWriteClicked(dummyData)
        assertEquals(EditAction.WRITE, viewModel.pendingEditAction.value)
        assertEquals(EditStatusType.PENDING, viewModel.editStatusType.value)

        // Toggle AFI trigger
        viewModel.onEditToggleAfiClicked()
        assertEquals(EditAction.TOGGLE_AFI, viewModel.pendingEditAction.value)
        assertEquals(EditStatusType.PENDING, viewModel.editStatusType.value)
    }

    @Test
    fun testTriggerTestEditScanPopulatesFields() {
        val testData = ch.infoage.nfcreader.nfc.FinnishLibraryData(
            uid = "E004015099887766",
            version = 1,
            usageType = 2,
            parts = 3,
            partNo = 1,
            itemId = "30118499",
            country = "CH",
            isil = "ISIL-42",
            isCrcValid = true,
            isTagEmpty = false,
            afi = "C7"
        )

        viewModel.triggerTestEditScan(testData)

        assertEquals("E004015099887766", viewModel.editUid.value)
        assertEquals("ISO 15693 (NfcV - Test)", viewModel.editTagType.value)
        assertEquals("C7", viewModel.editAfi.value)
        assertEquals(EditStatusType.SUCCESS, viewModel.editStatusType.value)
        assertTrue(viewModel.editHexDump.value.contains("Block 00:"))
        assertNotNull(viewModel.editLibraryData.value)
        assertEquals("30118499", viewModel.editLibraryData.value?.itemId)
        assertEquals("CH", viewModel.editLibraryData.value?.country)
        assertEquals("ISIL-42", viewModel.editLibraryData.value?.isil)
        assertEquals(1, viewModel.editLibraryData.value?.partNo)
        assertEquals(3, viewModel.editLibraryData.value?.parts)
        assertEquals(2, viewModel.editLibraryData.value?.usageType)
        assertTrue(viewModel.editLibraryData.value?.isCrcValid == true)
    }

    @Test
    fun testTriggerTestEditWriteUpdatesState() {
        val newData = ch.infoage.nfcreader.nfc.FinnishLibraryData(
            uid = "E004015088776655",
            version = 1,
            usageType = 1,
            parts = 2,
            partNo = 2,
            itemId = "NEW-BARCODE",
            country = "DE",
            isil = "ISIL-99",
            isCrcValid = true,
            isTagEmpty = false,
            afi = "07"
        )

        viewModel.triggerTestEditWrite(newData)

        assertEquals(EditStatusType.SUCCESS, viewModel.editStatusType.value)
        assertEquals("E004015088776655", viewModel.editUid.value)
        assertEquals("07", viewModel.editAfi.value)
        assertEquals("NEW-BARCODE", viewModel.editLibraryData.value?.itemId)
        assertEquals("DE", viewModel.editLibraryData.value?.country)
        assertEquals("ISIL-99", viewModel.editLibraryData.value?.isil)
        assertEquals(2, viewModel.editLibraryData.value?.partNo)
        assertEquals(2, viewModel.editLibraryData.value?.parts)
        assertTrue(viewModel.editLibraryData.value?.isCrcValid == true)
    }

    @Test
    fun testTriggerTestEditToggleAfi() {
        // Initially set AFI to C7 (Gesichert)
        val testData = ch.infoage.nfcreader.nfc.FinnishLibraryData(
            uid = "E004015011112222",
            version = 1,
            usageType = 1,
            parts = 1,
            partNo = 1,
            itemId = "TEST",
            country = "CH",
            isil = "ISIL-1",
            isCrcValid = true,
            isTagEmpty = false,
            afi = "C7"
        )
        viewModel.triggerTestEditScan(testData)
        assertEquals("C7", viewModel.editAfi.value)

        // Toggle AFI: C7 -> 07 (Ausgeliehen)
        viewModel.triggerTestEditToggleAfi()
        assertEquals("07", viewModel.editAfi.value)
        assertEquals("07", viewModel.editLibraryData.value?.afi)
        assertEquals(EditStatusType.SUCCESS, viewModel.editStatusType.value)

        // Toggle AFI again: 07 -> C7 (Gesichert)
        viewModel.triggerTestEditToggleAfi()
        assertEquals("C7", viewModel.editAfi.value)
        assertEquals("C7", viewModel.editLibraryData.value?.afi)
        assertEquals(EditStatusType.SUCCESS, viewModel.editStatusType.value)
    }
}
