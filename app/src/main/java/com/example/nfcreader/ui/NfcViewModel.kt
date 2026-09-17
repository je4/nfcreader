package com.example.nfcreader.ui

import android.nfc.Tag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nfcreader.data.model.NfcScanResult
import com.example.nfcreader.data.network.UrlDispatcher
import com.example.nfcreader.nfc.Iso15693Parser
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
            content = content
        )
    }

    fun triggerTestScan(mockContent: String? = null) {
        val randomSuffix = (1000..9999).random()
        val mockUid = "E0040150${randomSuffix}ABCD"
        val content = mockContent ?: "ISO15693-DATA-BLOCK-$randomSuffix"

        processScan(
            uid = mockUid,
            tagType = "ISO 15693 (NfcV - Test)",
            content = content
        )
    }

    fun processScan(uid: String, tagType: String, content: String) {
        val currentText = _userText.value
        val currentUrl = _targetUrl.value
        val currentMethod = _httpMethod.value
        val currentJwt = _jwtKey.value

        // Throttle identical scans within 500ms
        val now = System.currentTimeMillis()
        if (uid == lastScannedUid && (now - lastScannedTime) < 500) {
            return
        }
        lastScannedUid = uid
        lastScannedTime = now

        viewModelScope.launch {
            val pendingScan = NfcScanResult(
                uid = uid,
                tagType = tagType,
                content = content,
                userText = currentText,
                requestUrl = currentUrl,
                httpMethod = currentMethod
            )

            val result = urlDispatcher.dispatchScan(
                targetUrlTemplate = currentUrl,
                userText = currentText,
                nfcContent = content,
                uid = uid,
                httpMethod = currentMethod,
                jwtToken = currentJwt
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
