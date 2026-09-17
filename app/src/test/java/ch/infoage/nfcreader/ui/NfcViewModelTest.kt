package ch.infoage.nfcreader.ui

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
            content = "PAYLOAD-BLOCK-DATA"
        )

        advanceUntilIdle()

        val recorded = mockWebServer.takeRequest()
        assertEquals("Bearer jwt-secret-abc", recorded.getHeader("Authorization"))

        val lastScan = viewModel.lastScan.value
        assertNotNull(lastScan)
        assertEquals("E004015099887766", lastScan?.uid)
        assertEquals("Kiste A-9", lastScan?.userText)
        assertEquals("PAYLOAD-BLOCK-DATA", lastScan?.content)
        assertEquals(200, lastScan?.httpStatus)
        assertTrue(lastScan?.isSuccess == true)

        val history = viewModel.scanHistory.value
        assertEquals(1, history.size)
        assertEquals(lastScan, history[0])

        viewModel.clearHistory()
        assertTrue(viewModel.scanHistory.value.isEmpty())
        assertEquals(null, viewModel.lastScan.value)
    }
}
