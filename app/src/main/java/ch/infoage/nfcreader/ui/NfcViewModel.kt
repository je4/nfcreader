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
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.infoage.nfcreader.data.local.AppSettings
import ch.infoage.nfcreader.data.model.NfcScanResult
import ch.infoage.nfcreader.data.network.JwtGenerator
import ch.infoage.nfcreader.data.network.UrlDispatcher
import ch.infoage.nfcreader.nfc.FinnishDataModelParser
import ch.infoage.nfcreader.nfc.FinnishLibraryData
import ch.infoage.nfcreader.nfc.Iso15693Parser
import ch.infoage.nfcreader.nfc.Iso15693Writer
import ch.infoage.nfcreader.nfc.ParsedNfcTag
import ch.infoage.nfcreader.nfc.TagIdentifierType
import ch.infoage.nfcreader.nfc.TransponderDetails
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AppTab {
    SCAN,
    EDIT
}

enum class EditAction {
    NONE,
    READ,
    WRITE,
    TOGGLE_AFI
}

enum class EditStatusType {
    NEUTRAL,
    SUCCESS,
    ERROR,
    PENDING
}

private data class ScanDataSignature(
    val uid: String,
    val content: String,
    val rawPayloadHex: String,
    val location: String,
    val targetUrl: String,
    val httpMethod: String,
    val libraryData: FinnishLibraryData?
)

class NfcViewModel(
    private val urlDispatcher: UrlDispatcher = UrlDispatcher()
) : ViewModel() {

    // Tab Navigation
    private val _currentTab = MutableStateFlow(AppTab.SCAN)
    val currentTab: StateFlow<AppTab> = _currentTab.asStateFlow()

    // Scan Mode State
    private val _userText = MutableStateFlow("")
    val userText: StateFlow<String> = _userText.asStateFlow()

    private val _targetUrl = MutableStateFlow("https://httpbin.org/get")
    val targetUrl: StateFlow<String> = _targetUrl.asStateFlow()

    private val _httpMethod = MutableStateFlow("GET")
    val httpMethod: StateFlow<String> = _httpMethod.asStateFlow()

    private val _jwtKey = MutableStateFlow("")
    val jwtKey: StateFlow<String> = _jwtKey.asStateFlow()

    private val _debugMode = MutableStateFlow(false)
    val debugMode: StateFlow<Boolean> = _debugMode.asStateFlow()

    private val _afiPassword = MutableStateFlow(AppSettings.DEFAULT_AFI_PASSWORD)
    val afiPassword: StateFlow<String> = _afiPassword.asStateFlow()

    private val _useAfiPassword = MutableStateFlow(false)
    val useAfiPassword: StateFlow<Boolean> = _useAfiPassword.asStateFlow()

    private val _writePassword = MutableStateFlow(AppSettings.DEFAULT_WRITE_PASSWORD)
    val writePassword: StateFlow<String> = _writePassword.asStateFlow()

    private val _useWritePassword = MutableStateFlow(false)
    val useWritePassword: StateFlow<Boolean> = _useWritePassword.asStateFlow()

    private val _isScanningActive = MutableStateFlow(false)
    val isScanningActive: StateFlow<Boolean> = _isScanningActive.asStateFlow()

    private val _lastScan = MutableStateFlow<NfcScanResult?>(null)
    val lastScan: StateFlow<NfcScanResult?> = _lastScan.asStateFlow()

    private val _scanHistory = MutableStateFlow<List<NfcScanResult>>(emptyList())
    val scanHistory: StateFlow<List<NfcScanResult>> = _scanHistory.asStateFlow()

    // Edit Mode State
    private val _editLibraryData = MutableStateFlow<FinnishLibraryData?>(null)
    val editLibraryData: StateFlow<FinnishLibraryData?> = _editLibraryData.asStateFlow()

    private val _editUid = MutableStateFlow("")
    val editUid: StateFlow<String> = _editUid.asStateFlow()

    private val _editTagType = MutableStateFlow("")
    val editTagType: StateFlow<String> = _editTagType.asStateFlow()

    private val _editRawPayloadHex = MutableStateFlow("")
    val editRawPayloadHex: StateFlow<String> = _editRawPayloadHex.asStateFlow()

    private val _editHexDump = MutableStateFlow("")
    val editHexDump: StateFlow<String> = _editHexDump.asStateFlow()

    private val _editAfi = MutableStateFlow("")
    val editAfi: StateFlow<String> = _editAfi.asStateFlow()

    private val _editTransponderDetails = MutableStateFlow<TransponderDetails?>(null)
    val editTransponderDetails: StateFlow<TransponderDetails?> = _editTransponderDetails.asStateFlow()

    private val _editStatus = MutableStateFlow("Bereit. Wählen Sie eine Aktion (z.B. 'Lesen').")
    val editStatus: StateFlow<String> = _editStatus.asStateFlow()

    private val _editStatusType = MutableStateFlow(EditStatusType.NEUTRAL)
    val editStatusType: StateFlow<EditStatusType> = _editStatusType.asStateFlow()

    private val _pendingEditAction = MutableStateFlow(EditAction.NONE)
    val pendingEditAction: StateFlow<EditAction> = _pendingEditAction.asStateFlow()

    private var pendingWriteData: FinnishLibraryData? = null

    // Keep track of the last processed scan data to prevent duplicate web-service calls for identical data
    private var lastScannedData: ScanDataSignature? = null
    private var lastScannedTime: Long = 0

    fun setTab(tab: AppTab) {
        _currentTab.value = tab
        if (tab == AppTab.EDIT) {
            _isScanningActive.value = false
        }
    }

    fun setUserText(text: String) {
        _userText.value = text
        if (text.isEmpty() && _isScanningActive.value) {
            _isScanningActive.value = false
        }
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

    fun setDebugMode(debug: Boolean) {
        _debugMode.value = debug
    }

    fun setAfiPassword(password: String) {
        _afiPassword.value = AppSettings.normalizeHex32(password)
    }

    fun setUseAfiPassword(use: Boolean) {
        _useAfiPassword.value = use
    }

    fun setWritePassword(password: String) {
        _writePassword.value = AppSettings.normalizeHex32(password)
    }

    fun setUseWritePassword(use: Boolean) {
        _useWritePassword.value = use
    }

    fun setScanningActive(active: Boolean) {
        if (active && (_userText.value.isEmpty() || _currentTab.value == AppTab.EDIT)) {
            _isScanningActive.value = false
            return
        }
        _isScanningActive.value = active
    }

    fun clearHistory() {
        _scanHistory.value = emptyList()
        _lastScan.value = null
        lastScannedData = null
        lastScannedTime = 0
    }

    fun onEditReadClicked() {
        _pendingEditAction.value = EditAction.READ
        _editStatus.value = "Halten Sie ein ISO 15693 Tag an die Rückseite zum Lesen..."
        _editStatusType.value = EditStatusType.PENDING
    }

    fun onEditWriteClicked(data: FinnishLibraryData) {
        pendingWriteData = data
        _pendingEditAction.value = EditAction.WRITE
        _editStatus.value = "Halten Sie ein ISO 15693 Tag an die Rückseite zum Schreiben..."
        _editStatusType.value = EditStatusType.PENDING
    }

    fun onEditToggleAfiClicked() {
        _pendingEditAction.value = EditAction.TOGGLE_AFI
        val currentAfi = _editAfi.value.trim().uppercase()
        val targetAfi = if (currentAfi == "C2") "07" else "C2"
        _editStatus.value = "Halten Sie ein Tag an zum Umschalten von AFI ($currentAfi -> $targetAfi)..."
        _editStatusType.value = EditStatusType.PENDING
    }

    fun updateEditLibraryData(data: FinnishLibraryData) {
        _editLibraryData.value = data
        _editAfi.value = data.afi
    }

    fun handleTagDiscovered(tag: Tag) {
        if (_currentTab.value == AppTab.SCAN) {
            if (!_isScanningActive.value || _userText.value.isEmpty()) return

            val parsed = Iso15693Parser.parseTag(tag)
            val content = parsed.textContent ?: parsed.rawPayloadHex

            processScan(
                uid = parsed.uid,
                tagType = parsed.tagType,
                content = content,
                rawPayloadHex = parsed.rawPayloadHex,
                libraryData = parsed.libraryData
            )
        } else {
            handleEditTagDiscovered(tag)
        }
    }

    fun handleEditTagDiscovered(tag: Tag) {
        if (_pendingEditAction.value == EditAction.NONE) {
            return
        }

        if (!Iso15693Writer.isIso15693(tag)) {
            _editStatus.value = "Nur ISO 15693 (NfcV) Tags werden im Edit-Modus unterstützt."
            _editStatusType.value = EditStatusType.ERROR
            _pendingEditAction.value = EditAction.NONE
            return
        }

        when (_pendingEditAction.value) {
            EditAction.WRITE -> {
                val toWrite = pendingWriteData
                if (toWrite == null) {
                    _editStatus.value = "Keine Daten zum Schreiben vorhanden."
                    _editStatusType.value = EditStatusType.ERROR
                    _pendingEditAction.value = EditAction.NONE
                    return
                }

                val writeResult = Iso15693Writer.writeFinnishData(tag, toWrite)
                if (writeResult.isSuccess) {
                    val parsed = writeResult.getOrThrow()
                    applyParsedEditTag(parsed)
                    _editStatus.value = "Tag erfolgreich geschrieben!"
                    _editStatusType.value = EditStatusType.SUCCESS
                } else {
                    _editStatus.value = "Fehler beim Schreiben: ${writeResult.exceptionOrNull()?.message ?: "Unbekannt"}"
                    _editStatusType.value = EditStatusType.ERROR
                }
                _pendingEditAction.value = EditAction.NONE
                pendingWriteData = null
            }
            EditAction.TOGGLE_AFI -> {
                val currentAfi = _editAfi.value.trim().uppercase()
                val targetAfi = if (currentAfi == "C2") "07" else "C2"

                val afiResult = Iso15693Writer.writeAfi(tag, targetAfi)
                if (afiResult.isSuccess) {
                    val newAfi = afiResult.getOrThrow()
                    _editAfi.value = newAfi
                    _editLibraryData.value = _editLibraryData.value?.copy(afi = newAfi)
                    _editStatus.value = "AFI Status erfolgreich auf $newAfi gesetzt!"
                    _editStatusType.value = EditStatusType.SUCCESS
                } else {
                    _editStatus.value = "Fehler beim Setzen von AFI: ${afiResult.exceptionOrNull()?.message ?: "Unbekannt"}"
                    _editStatusType.value = EditStatusType.ERROR
                }
                _pendingEditAction.value = EditAction.NONE
            }
            EditAction.READ -> {
                val readResult = Iso15693Writer.readTag(tag)
                if (readResult.isSuccess) {
                    val parsed = readResult.getOrThrow()
                    applyParsedEditTag(parsed)
                    _editStatus.value = "Tag erfolgreich gelesen (UID: ${parsed.uid})"
                    _editStatusType.value = EditStatusType.SUCCESS
                } else {
                    _editStatus.value = "Fehler beim Lesen: ${readResult.exceptionOrNull()?.message ?: "Unbekannt"}"
                    _editStatusType.value = EditStatusType.ERROR
                }
                _pendingEditAction.value = EditAction.NONE
            }
            EditAction.NONE -> {
                // Nur lesen, wenn der Button 'Lesen' gedrückt wurde
            }
        }
    }

    private fun applyParsedEditTag(parsed: ParsedNfcTag) {
        _editUid.value = parsed.uid
        _editTagType.value = parsed.tagType
        _editRawPayloadHex.value = parsed.rawPayloadHex
        _editAfi.value = parsed.afi ?: parsed.libraryData?.afi ?: ""
        _editTransponderDetails.value = parsed.transponderDetails ?: Iso15693Parser.parseTransponderDetails(parsed.uid)
        val rawBytes = try {
            if (parsed.rawPayloadHex.isNotBlank()) {
                val len = parsed.rawPayloadHex.length
                val data = ByteArray(len / 2)
                for (i in 0 until len step 2) {
                    data[i / 2] = ((Character.digit(parsed.rawPayloadHex[i], 16) shl 4) +
                            Character.digit(parsed.rawPayloadHex[i + 1], 16)).toByte()
                }
                data
            } else null
        } catch (e: Exception) {
            null
        }
        _editHexDump.value = Iso15693Writer.formatHexDump(rawBytes)
        _editLibraryData.value = parsed.libraryData ?: FinnishLibraryData(
            uid = parsed.uid,
            version = 1,
            usageType = 1,
            parts = 1,
            partNo = 1,
            itemId = "",
            country = "",
            isil = "",
            isCrcValid = false,
            isTagEmpty = true,
            afi = parsed.afi ?: ""
        )
    }

    fun triggerTestEditScan(mockData: FinnishLibraryData? = null, mockUid: String? = null) {
        val targetUid = mockUid ?: mockData?.uid?.takeIf { it.isNotBlank() }
        if (targetUid != null && targetUid.replace(":", "").replace(" ", "").trim().uppercase().startsWith("E2")) {
            val cleanTid = targetUid.replace(":", "").replace(" ", "").trim().uppercase()
            val tagType = Iso15693Parser.resolveIso15693TagType(cleanTid)
            val details = Iso15693Parser.parseTransponderDetails(cleanTid)
            val parsed = ParsedNfcTag(
                uid = cleanTid,
                tagType = tagType,
                rawPayloadHex = cleanTid,
                textContent = null,
                fullSummary = "TID: $cleanTid ($tagType)",
                libraryData = null,
                afi = null,
                identifierType = TagIdentifierType.EPC_GEN2_TID,
                transponderDetails = details
            )
            applyParsedEditTag(parsed)
            _editStatus.value = "Test-TID erfolgreich geladen ($cleanTid)"
            _editStatusType.value = EditStatusType.SUCCESS
            _pendingEditAction.value = EditAction.NONE
            return
        }

        val randomSuffix = (1000..9999).random()
        val resolvedUid = targetUid ?: "E0040150${randomSuffix}ABCD"
        val testData = mockData ?: FinnishLibraryData(
            uid = resolvedUid,
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
            afi = "C2"
        )
        val encoded = FinnishDataModelParser.encode(testData)
        val hex = Iso15693Parser.bytesToHex(encoded)
        val transponderDetails = Iso15693Parser.parseTransponderDetails(resolvedUid)
        val parsed = ParsedNfcTag(
            uid = resolvedUid,
            tagType = Iso15693Parser.resolveIso15693TagType(resolvedUid),
            rawPayloadHex = hex,
            textContent = testData.toFormattedString(),
            fullSummary = testData.toFormattedString(),
            libraryData = testData,
            afi = testData.afi,
            identifierType = TagIdentifierType.ISO15693_UID,
            transponderDetails = transponderDetails
        )
        applyParsedEditTag(parsed)
        _editStatus.value = "Test-Tag erfolgreich geladen (UID: $resolvedUid)"
        _editStatusType.value = EditStatusType.SUCCESS
        _pendingEditAction.value = EditAction.NONE
    }

    fun triggerTestEditWrite(data: FinnishLibraryData) {
        val encoded = FinnishDataModelParser.encode(data)
        val hex = Iso15693Parser.bytesToHex(encoded)
        val parsed = FinnishDataModelParser.parse(data.uid.ifBlank { "E00401509999ABCD" }, encoded, afi = data.afi)
        val finalData = parsed ?: data
        val transponderDetails = Iso15693Parser.parseTransponderDetails(finalData.uid)
        val parsedTag = ParsedNfcTag(
            uid = finalData.uid,
            tagType = Iso15693Parser.resolveIso15693TagType(finalData.uid),
            rawPayloadHex = hex,
            textContent = finalData.toFormattedString(),
            fullSummary = finalData.toFormattedString(),
            libraryData = finalData,
            afi = finalData.afi,
            identifierType = Iso15693Parser.resolveIdentifierType(finalData.uid),
            transponderDetails = transponderDetails
        )
        applyParsedEditTag(parsedTag)
        _editStatus.value = "Test-Tag erfolgreich geschrieben!"
        _editStatusType.value = EditStatusType.SUCCESS
        _pendingEditAction.value = EditAction.NONE
        pendingWriteData = null
    }

    fun triggerTestEditToggleAfi() {
        val currentAfi = _editAfi.value.trim().uppercase()
        val targetAfi = if (currentAfi == "C2") "07" else "C2"
        _editAfi.value = targetAfi
        val currentData = _editLibraryData.value
        if (currentData != null) {
            _editLibraryData.value = currentData.copy(afi = targetAfi)
        }
        _editStatus.value = "AFI Status erfolgreich auf $targetAfi gesetzt!"
        _editStatusType.value = EditStatusType.SUCCESS
        _pendingEditAction.value = EditAction.NONE
    }

    fun triggerTestScan(mockContent: String? = null) {
        if (_userText.value.isEmpty()) return

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
            afi = "C2"
        )
        val content = mockContent ?: mockLibraryData.toFormattedString()
        val rawPayloadHex = FinnishDataModelParser.encode(mockLibraryData).let {
            Iso15693Parser.bytesToHex(it)
        }

        processScan(
            uid = mockUid,
            tagType = Iso15693Parser.resolveIso15693TagType(mockUid),
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

        val currentData = ScanDataSignature(
            uid = uid,
            content = content,
            rawPayloadHex = rawPayloadHex,
            location = currentText,
            targetUrl = currentUrl,
            httpMethod = currentMethod,
            libraryData = libraryData
        )

        // Ignore duplicate scans with identical data
        val now = System.currentTimeMillis()
        if (currentData == lastScannedData) {
            val last = _lastScan.value
            // If the last call was successful or is still pending/recent, do not call web service again
            if (last == null || last.isSuccess || (now - lastScannedTime) < 1000) {
                return
            }
        }
        lastScannedData = currentData
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

            val preparedRequest = try {
                urlDispatcher.prepareRequest(
                    targetUrlTemplate = currentUrl,
                    marker = currentText,
                    nfcContent = content,
                    rawPayloadHex = rawPayloadHex,
                    uid = uid,
                    httpMethod = currentMethod,
                    jwtToken = jwtToken,
                    jwtKey = currentKey,
                    libraryData = libraryData,
                    timestamp = now
                )
            } catch (e: Exception) {
                null
            }

            val pendingScan = NfcScanResult(
                uid = uid,
                tagType = tagType,
                content = content,
                rawPayloadHex = rawPayloadHex,
                location = currentText,
                requestUrl = preparedRequest?.fullUrl ?: currentUrl,
                httpMethod = currentMethod,
                jwtKey = currentKey.takeIf { it.isNotBlank() },
                requestHeaders = preparedRequest?.headers ?: emptyMap(),
                requestBody = preparedRequest?.body,
                httpRequestDebug = preparedRequest?.debugString,
                libraryData = libraryData
            )

            if (_debugMode.value && preparedRequest != null) {
                try {
                    Log.d("NfcHttpDebug", "=== HTTP REQUEST (DEBUG) ===\n${preparedRequest.debugString}")
                } catch (e: Throwable) {
                    println("=== HTTP REQUEST (DEBUG) ===\n${preparedRequest.debugString}")
                }
            }

            val result = urlDispatcher.dispatchScan(
                targetUrlTemplate = currentUrl,
                marker = currentText,
                nfcContent = content,
                rawPayloadHex = rawPayloadHex,
                uid = uid,
                httpMethod = currentMethod,
                jwtToken = jwtToken,
                jwtKey = currentKey,
                libraryData = libraryData
            )

            val completedScan = if (result.isSuccess) {
                val response = result.getOrThrow()
                pendingScan.copy(
                    requestUrl = response.requestUrl,
                    httpStatus = response.httpStatus,
                    responseBody = response.responseBody,
                    isSuccess = true,
                    durationMs = response.durationMs,
                    requestHeaders = response.requestHeaders.ifEmpty { pendingScan.requestHeaders },
                    requestBody = response.requestBody ?: pendingScan.requestBody,
                    httpRequestDebug = response.httpRequestDebug.ifBlank { pendingScan.httpRequestDebug }
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
