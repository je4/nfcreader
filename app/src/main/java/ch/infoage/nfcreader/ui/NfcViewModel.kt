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

import android.nfc.Tag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.infoage.nfcreader.data.model.NfcScanResult
import ch.infoage.nfcreader.data.network.JwtGenerator
import ch.infoage.nfcreader.data.network.UrlDispatcher
import ch.infoage.nfcreader.nfc.FinnishLibraryData
import ch.infoage.nfcreader.nfc.Iso15693Parser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NfcViewModel(
    private val urlDispatcher: UrlDispatcher = UrlDispatcher()
) : ViewModel() {

    private val _userText = MutableStateFlow("")
    val userText: StateFlow<String> = _userText.asStateFlow()

    private val _targetUrl = MutableStateFlow("https://httpbin.org/get")
    val targetUrl: StateFlow<String> = _targetUrl.asStateFlow()

    private val _httpMethod = MutableStateFlow("GET")
    val httpMethod: StateFlow<String> = _httpMethod.asStateFlow()

    private val _jwtKey = MutableStateFlow("")
    val jwtKey: StateFlow<String> = _jwtKey.asStateFlow()

    private val _isScanningActive = MutableStateFlow(true)
    val isScanningActive: StateFlow<Boolean> = _isScanningActive.asStateFlow()

    private val _lastScan = MutableStateFlow<NfcScanResult?>(null)
    val lastScan: StateFlow<NfcScanResult?> = _lastScan.asStateFlow()

    private val _scanHistory = MutableStateFlow<List<NfcScanResult>>(emptyList())
    val scanHistory: StateFlow<List<NfcScanResult>> = _scanHistory.asStateFlow()

    // Keep track of the last processed content and time to prevent duplicate rapid fires if needed
    private var lastScannedUid: String? = null
    private var lastScannedTime: Long = 0

    fun setUserText(text: String) {
        _userText.value = text
    }

    fun setTargetUrl(url: String) {
        _targetUrl.value = url
    }

    fun setHttpMethod(method: String) {
        _httpMethod.value = method
    }

    fun setJwtKey(key: String) {
        _jwtKey.value = key
    }

    fun setScanningActive(active: Boolean) {
        _isScanningActive.value = active
    }

    fun clearHistory() {
        _scanHistory.value = emptyList()
        _lastScan.value = null
    }

    fun handleTagDiscovered(tag: Tag) {
        if (!_isScanningActive.value) return

        val parsed = Iso15693Parser.parseTag(tag)
        val content = parsed.textContent ?: parsed.rawPayloadHex

        processScan(
            uid = parsed.uid,
            tagType = parsed.tagType,
            content = content,
            rawPayloadHex = parsed.rawPayloadHex,
            libraryData = parsed.libraryData
        )
    }

    fun triggerTestScan(mockContent: String? = null) {
        val randomSuffix = (1000..9999).random()
        val mockUid = "E0040150${randomSuffix}ABCD"
        val mockLibraryData = FinnishLibraryData(
            uid = mockUid,
            version = 1,
            usageType = 1,
            parts = 1,
            partNo = 1,
            itemId = "3011$randomSuffix",
            country = "CH",
            isil = "ISIL-123",
            isCrcValid = true,
            isTagEmpty = false,
            crcHex = "A1B2",
            afi = "C7"
        )
        val content = mockContent ?: mockLibraryData.toFormattedString()
        val rawPayloadHex = ch.infoage.nfcreader.nfc.FinnishDataModelParser.encode(mockLibraryData).let {
            Iso15693Parser.bytesToHex(it)
        }

        processScan(
            uid = mockUid,
            tagType = "ISO 15693 (NfcV - Test)",
            content = content,
            rawPayloadHex = rawPayloadHex,
            libraryData = if (mockContent == null) mockLibraryData else null
        )
    }

    fun processScan(
        uid: String,
        tagType: String,
        content: String,
        rawPayloadHex: String = "",
        libraryData: FinnishLibraryData? = null
    ) {
        val currentText = _userText.value
        val currentUrl = _targetUrl.value
        val currentMethod = _httpMethod.value
        val currentKey = _jwtKey.value

        // Throttle identical scans within 500ms
        val now = System.currentTimeMillis()
        if (uid == lastScannedUid && (now - lastScannedTime) < 500) {
            return
        }
        lastScannedUid = uid
        lastScannedTime = now

        viewModelScope.launch {
            val jwtToken = if (currentKey.isNotBlank()) {
                JwtGenerator.generateToken(
                    secret = currentKey,
                    validitySeconds = 60,
                    issuedAtMillis = now
                )
            } else {
                null
            }

            val pendingScan = NfcScanResult(
                uid = uid,
                tagType = tagType,
                content = content,
                rawPayloadHex = rawPayloadHex,
                userText = currentText,
                requestUrl = currentUrl,
                httpMethod = currentMethod,
                libraryData = libraryData
            )

            val result = urlDispatcher.dispatchScan(
                targetUrlTemplate = currentUrl,
                userText = currentText,
                nfcContent = content,
                rawPayloadHex = rawPayloadHex,
                uid = uid,
                httpMethod = currentMethod,
                jwtToken = jwtToken,
                libraryData = libraryData
            )

            val completedScan = if (result.isSuccess) {
                val response = result.getOrThrow()
                pendingScan.copy(
                    requestUrl = response.requestUrl,
                    httpStatus = response.httpStatus,
                    responseBody = response.responseBody,
                    isSuccess = true,
                    durationMs = response.durationMs
                )
            } else {
                val error = result.exceptionOrNull()
                pendingScan.copy(
                    errorMessage = error?.localizedMessage ?: "Unbekannter Fehler",
                    isSuccess = false
                )
            }

            _lastScan.value = completedScan
            _scanHistory.value = listOf(completedScan) + _scanHistory.value
        }
    }
}
