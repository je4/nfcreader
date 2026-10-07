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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import ch.infoage.nfcreader.R
import ch.infoage.nfcreader.data.local.AppSettings
import ch.infoage.nfcreader.databinding.ActivityMainBinding
import ch.infoage.nfcreader.nfc.FinnishLibraryData
import ch.infoage.nfcreader.nfc.NfcReaderManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: NfcViewModel by viewModels()
    private lateinit var nfcManager: NfcReaderManager
    private lateinit var appSettings: AppSettings
    private val historyAdapter = ScanHistoryAdapter()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appSettings = AppSettings(this)

        nfcManager = NfcReaderManager(this) { tag ->
            runOnUiThread {
                viewModel.handleTagDiscovered(tag)
            }
        }

        setupViews()
        observeViewModel()

        // Handle initial intent if app was launched via NFC Tag tap
        intent?.let { handleNfcIntent(it) }
    }

    private fun setupViews() {
        // Toolbar menu
        binding.toolbar.inflateMenu(R.menu.menu_main)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_settings) {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            } else {
                false
            }
        }

        // Bottom Navigation Tab Switcher
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_scan -> {
                    viewModel.setTab(AppTab.SCAN)
                    true
                }
                R.id.nav_edit -> {
                    viewModel.setTab(AppTab.EDIT)
                    true
                }
                else -> false
            }
        }

        // Setup Scan Mode Views
        binding.rvScanHistory.layoutManager = LinearLayoutManager(this)
        binding.rvScanHistory.adapter = historyAdapter

        binding.etUserText.doAfterTextChanged { editable ->
            viewModel.setUserText(editable?.toString().orEmpty())
        }

        binding.switchContinuousScan.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && viewModel.userText.value.isEmpty()) {
                binding.switchContinuousScan.isChecked = false
                return@setOnCheckedChangeListener
            }
            viewModel.setScanningActive(isChecked)
            updateNfcStatus()
            updateNfcReaderMode()
        }

        binding.btnTestScan.setOnClickListener {
            viewModel.triggerTestScan()
        }

        binding.tvClearHistory.setOnClickListener {
            viewModel.clearHistory()
        }

        // Setup Edit Mode Views
        binding.btnEditRead.setOnClickListener {
            viewModel.onEditReadClicked()
        }

        binding.btnEditWrite.setOnClickListener {
            val toWrite = createLibraryDataFromEditInputs()
            viewModel.onEditWriteClicked(toWrite)
        }

        binding.btnEditToggleAfi.setOnClickListener {
            viewModel.onEditToggleAfiClicked()
        }

        var testTagIndex = 0
        val testTags = listOf(
            null, // ISO 15693 Finnish Library Tag (NXP ICODE SLIX)
            "E28069952000500101D589CC", // NXP UCODE 9 Extended TID (GS1 TDS 2.0)
            "E2801160", // Impinj Monza R6 Short TID
            "E28034142000200080004140" // Alien Higgs 4 Extended TID
        )

        binding.btnEditTestScan.setOnClickListener {
            val selected = testTags[testTagIndex % testTags.size]
            testTagIndex++
            if (selected == null) {
                viewModel.triggerTestEditScan()
            } else {
                viewModel.triggerTestEditScan(mockUid = selected)
            }
        }
    }

    private fun createLibraryDataFromEditInputs(): FinnishLibraryData {
        val itemId = binding.etEditItemId.text?.toString().orEmpty().trim()
        val country = binding.etEditCountry.text?.toString().orEmpty().trim().uppercase()
        val isil = binding.etEditIsil.text?.toString().orEmpty().trim()
        val partNo = binding.etEditPartNo.text?.toString()?.toIntOrNull() ?: 1
        val parts = binding.etEditParts.text?.toString()?.toIntOrNull() ?: 1
        val usageType = binding.etEditUsageType.text?.toString()?.toIntOrNull() ?: 1
        val version = binding.etEditVersion.text?.toString()?.toIntOrNull() ?: 1
        val afi = binding.etEditAfi.text?.toString().orEmpty().trim().uppercase()
        val uid = binding.tvEditUid.text?.toString().orEmpty().takeIf { it != "-" }.orEmpty()

        return FinnishLibraryData(
            uid = uid,
            version = version,
            usageType = usageType,
            parts = parts,
            partNo = partNo,
            itemId = itemId,
            country = country,
            isil = isil,
            isCrcValid = true,
            isTagEmpty = false,
            afi = afi
        )
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe User Text
                launch {
                    viewModel.userText.collectLatest { text ->
                        val hasLocation = text.isNotEmpty()
                        if (!hasLocation && binding.switchContinuousScan.isChecked) {
                            binding.switchContinuousScan.isChecked = false
                        }
                        updateNfcStatus()
                    }
                }

                // Observe Scanning Active
                launch {
                    viewModel.isScanningActive.collectLatest { isActive ->
                        if (binding.switchContinuousScan.isChecked != isActive) {
                            binding.switchContinuousScan.isChecked = isActive
                        }
                        updateNfcStatus()
                        updateNfcReaderMode()
                    }
                }

                // Observe Tab Selection
                launch {
                    viewModel.currentTab.collectLatest { tab ->
                        val itemId = if (tab == AppTab.SCAN) R.id.nav_scan else R.id.nav_edit
                        if (binding.bottomNavigation.selectedItemId != itemId) {
                            binding.bottomNavigation.selectedItemId = itemId
                        }
                        binding.layoutScan.visibility = if (tab == AppTab.SCAN) View.VISIBLE else View.GONE
                        binding.layoutEdit.visibility = if (tab == AppTab.EDIT) View.VISIBLE else View.GONE
                        updateNfcReaderMode()
                    }
                }

                // Observe Scan History
                launch {
                    viewModel.scanHistory.collectLatest { history ->
                        historyAdapter.submitList(history)
                    }
                }

                launch {
                    viewModel.debugMode.collectLatest { isDebug ->
                        historyAdapter.setDebugMode(isDebug)
                    }
                }

                // Observe Last Scan in Scan Mode
                launch {
                    viewModel.lastScan.collectLatest { lastScan ->
                        if (lastScan != null) {
                            binding.tvLastScanTime.text = timeFormat.format(Date(lastScan.timestamp))
                            binding.tvLastScanDetails.text = buildString {
                                append("UID: ").append(lastScan.uid)
                                append(" (").append(lastScan.tagType).append(")\n")
                                if (lastScan.libraryData != null && !lastScan.libraryData.isTagEmpty) {
                                    val lib = lastScan.libraryData
                                    append("Item-ID: ").append(lib.itemId.ifBlank { "(keine)" }).append("\n")
                                    val isilStr = listOfNotNull(lib.country.takeIf { it.isNotBlank() }, lib.isil.takeIf { it.isNotBlank() })
                                        .joinToString("-")
                                    append("Bibliothek / ISIL: ").append(isilStr.ifBlank { "(keine)" }).append("\n")
                                    append("Teil: ").append(lib.partNo).append(" von ").append(lib.parts)
                                    append(" | Typ: ").append(lib.usageType)
                                    if (lib.afi.isNotBlank()) {
                                        val statusDesc = when (lib.afi.uppercase()) {
                                            "C2" -> getString(R.string.edit_afi_loaned)
                                            "07" -> getString(R.string.edit_afi_secured)
                                            else -> lib.afi
                                        }
                                        append(" | Status: ").append(statusDesc)
                                    }
                                    append(" | Version: ").append(lib.version)
                                    append(" | CRC: ").append(if (lib.isCrcValid) "OK" else "Fehler (${lib.crcHex})").append("\n")
                                }
                                append("Standort: \"").append(lastScan.location).append("\"")
                            }

                            binding.tvLastScanUrlCall.visibility = View.VISIBLE
                            val baseCallText = if (lastScan.isSuccess) {
                                "Aufruf erfolgreich (HTTP ${lastScan.httpStatus ?: 200}):\n${lastScan.requestUrl}"
                            } else {
                                "Aufruf fehlgeschlagen (${lastScan.errorMessage}):\n${lastScan.requestUrl}"
                            }

                            binding.tvLastScanUrlCall.text = baseCallText
                            if (lastScan.isSuccess) {
                                binding.tvLastScanUrlCall.setTextColor(
                                    ContextCompat.getColor(this@MainActivity, R.color.status_success)
                                )
                            } else {
                                binding.tvLastScanUrlCall.setTextColor(
                                    ContextCompat.getColor(this@MainActivity, R.color.status_error)
                                )
                            }

                            if (viewModel.debugMode.value && !lastScan.httpRequestDebug.isNullOrBlank()) {
                                binding.layoutLastScanDebug.visibility = View.VISIBLE
                                binding.tvLastScanDebugRequest.text = lastScan.httpRequestDebug
                                binding.btnCopyLastScanDebug.setOnClickListener {
                                    copyToClipboard(
                                        text = lastScan.httpRequestDebug,
                                        message = getString(R.string.debug_copied_to_clipboard)
                                    )
                                }
                            } else {
                                binding.layoutLastScanDebug.visibility = View.GONE
                            }
                        } else {
                            binding.tvLastScanTime.text = ""
                            binding.tvLastScanDetails.setText(R.string.no_scans_yet)
                            binding.tvLastScanUrlCall.visibility = View.GONE
                            binding.layoutLastScanDebug.visibility = View.GONE
                        }
                    }
                }

                // Observe Edit Mode States
                launch {
                    viewModel.editUid.collectLatest { uid ->
                        binding.tvEditUid.text = uid.ifBlank { "-" }
                    }
                }

                launch {
                    viewModel.editTagType.collectLatest { tagType ->
                        binding.tvEditTagType.text = tagType.ifBlank { "-" }
                    }
                }

                launch {
                    viewModel.editTransponderDetails.collectLatest { details ->
                        if (details != null) {
                            val epc = details.epcTid
                            val iso = details.iso15693

                            when {
                                epc != null -> {
                                    binding.layoutEditManufacturer.visibility = View.VISIBLE
                                    binding.layoutEditModel.visibility = View.VISIBLE
                                    binding.layoutEditFlags.visibility = View.VISIBLE
                                    binding.layoutEditSerial.visibility = View.VISIBLE

                                    val mfgText = "${epc.manufacturerName} (MDID: ${epc.mdidInt} / 0x${epc.mdidHex})"
                                    binding.tvEditManufacturer.text = mfgText

                                    val modelText = buildString {
                                        append(epc.modelName ?: "Unbekannt")
                                        if (epc.tmnHex.isNotEmpty()) {
                                            append(" (TMN: 0x").append(epc.tmnHex).append(")")
                                        }
                                    }
                                    binding.tvEditModel.text = modelText

                                    val flagsText = buildString {
                                        append("XTID: ").append(if (epc.hasXtid) "Ja" else "Nein")
                                        append(" | Sec: ").append(if (epc.hasSecurity) "Ja" else "Nein")
                                        append(" | File: ").append(if (epc.hasFileOpen) "Ja" else "Nein")
                                    }
                                    binding.tvEditFlags.text = flagsText

                                    binding.tvEditSerial.text = epc.serialNumberHex ?: "-"
                                }
                                iso != null -> {
                                    binding.layoutEditManufacturer.visibility = View.VISIBLE
                                    binding.layoutEditModel.visibility = View.VISIBLE
                                    binding.layoutEditFlags.visibility = View.GONE
                                    binding.layoutEditSerial.visibility = View.VISIBLE

                                    val mfgText = "${iso.manufacturerName} (Code: 0x${iso.mfgCodeHex})"
                                    binding.tvEditManufacturer.text = mfgText

                                    val modelText = if (iso.modelName != null) {
                                        "${iso.modelName} (Prod: 0x${iso.productCodeHex})"
                                    } else if (iso.productCodeHex.isNotEmpty()) {
                                        "Produktcode 0x${iso.productCodeHex}"
                                    } else {
                                        "-"
                                    }
                                    binding.tvEditModel.text = modelText

                                    binding.tvEditSerial.text = iso.serialNumberHex.ifBlank { "-" }
                                }
                                else -> {
                                    binding.layoutEditManufacturer.visibility = View.GONE
                                    binding.layoutEditModel.visibility = View.GONE
                                    binding.layoutEditFlags.visibility = View.GONE
                                    binding.layoutEditSerial.visibility = View.GONE
                                }
                            }
                        } else {
                            binding.layoutEditManufacturer.visibility = View.GONE
                            binding.layoutEditModel.visibility = View.GONE
                            binding.layoutEditFlags.visibility = View.GONE
                            binding.layoutEditSerial.visibility = View.GONE
                        }
                    }
                }

                launch {
                    viewModel.editAfi.collectLatest { afi ->
                        val afiDesc = when (afi.uppercase()) {
                            "C2" -> getString(R.string.edit_afi_loaned)
                            "07" -> getString(R.string.edit_afi_secured)
                            else -> if (afi.isNotBlank()) afi else "-"
                        }
                        binding.tvEditAfiStatus.text = afiDesc
                    }
                }

                launch {
                    viewModel.editHexDump.collectLatest { hexDump ->
                        binding.tvEditHexDump.text = hexDump.ifBlank { "Keine Rohdaten verfügbar." }
                    }
                }

                launch {
                    viewModel.editStatus.collectLatest { status ->
                        binding.tvEditStatus.text = status
                    }
                }

                launch {
                    viewModel.editStatusType.collectLatest { type ->
                        val colorRes = when (type) {
                            EditStatusType.SUCCESS -> R.color.status_success
                            EditStatusType.ERROR -> R.color.status_error
                            EditStatusType.PENDING -> R.color.status_pending
                            EditStatusType.NEUTRAL -> R.color.text_primary
                        }
                        binding.tvEditStatus.setTextColor(
                            ContextCompat.getColor(this@MainActivity, colorRes)
                        )
                    }
                }

                launch {
                    viewModel.editLibraryData.collectLatest { libData ->
                        if (libData != null) {
                            binding.etEditItemId.setText(libData.itemId)
                            binding.etEditCountry.setText(libData.country)
                            binding.etEditIsil.setText(libData.isil)
                            binding.etEditPartNo.setText(libData.partNo.toString())
                            binding.etEditParts.setText(libData.parts.toString())
                            binding.etEditUsageType.setText(libData.usageType.toString())
                            binding.etEditVersion.setText(libData.version.toString())
                            binding.etEditAfi.setText(libData.afi)

                            val crcText = if (libData.isCrcValid) {
                                getString(R.string.edit_crc_valid)
                            } else {
                                getString(R.string.edit_crc_invalid)
                            }
                            binding.tvEditCrcStatus.text = crcText
                            binding.tvEditCrcStatus.setTextColor(
                                ContextCompat.getColor(
                                    this@MainActivity,
                                    if (libData.isCrcValid) R.color.status_success else R.color.status_error
                                )
                            )
                        } else {
                            binding.tvEditCrcStatus.text = "-"
                            binding.tvEditCrcStatus.setTextColor(
                                ContextCompat.getColor(this@MainActivity, R.color.text_primary)
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Sync preferences with ViewModel
        viewModel.setTargetUrl(appSettings.targetUrl)
        viewModel.setHttpMethod(appSettings.httpMethod)
        viewModel.setJwtKey(appSettings.jwtKey)
        viewModel.setDebugMode(appSettings.debugMode)

        updateNfcStatus()
        updateNfcReaderMode()
    }

    override fun onPause() {
        super.onPause()
        nfcManager.stopContinuousScanning()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNfcIntent(intent)
    }

    private fun handleNfcIntent(intent: Intent) {
        val action = intent.action
        if (action == NfcAdapter.ACTION_TECH_DISCOVERED ||
            action == NfcAdapter.ACTION_TAG_DISCOVERED ||
            action == NfcAdapter.ACTION_NDEF_DISCOVERED
        ) {
            val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }
            if (tag != null) {
                viewModel.handleTagDiscovered(tag)
            }
        }
    }

    private fun updateNfcReaderMode() {
        if (viewModel.currentTab.value == AppTab.SCAN) {
            if (viewModel.isScanningActive.value && viewModel.userText.value.isNotEmpty()) {
                nfcManager.startContinuousScanning()
            } else {
                nfcManager.stopContinuousScanning()
            }
        } else {
            // In Edit Mode, enable reader mode so discovered tags are captured immediately
            nfcManager.startContinuousScanning()
        }
    }

    private fun updateNfcStatus() {
        val hasLocation = viewModel.userText.value.isNotEmpty()
        when (nfcManager.getAvailability()) {
            NfcReaderManager.NfcAvailability.ENABLED -> {
                binding.switchContinuousScan.isEnabled = hasLocation
                binding.btnTestScan.isEnabled = hasLocation
                binding.tvNfcStatus.text = if (viewModel.isScanningActive.value) {
                    getString(R.string.nfc_status_scanning)
                } else if (!hasLocation) {
                    getString(R.string.nfc_status_location_required)
                } else {
                    getString(R.string.nfc_status_paused)
                }
            }
            NfcReaderManager.NfcAvailability.DISABLED -> {
                binding.tvNfcStatus.text = getString(R.string.nfc_status_disabled)
                binding.switchContinuousScan.isEnabled = false
                binding.btnTestScan.isEnabled = false
            }
            NfcReaderManager.NfcAvailability.NOT_SUPPORTED -> {
                binding.tvNfcStatus.text = getString(R.string.nfc_status_unsupported)
                binding.switchContinuousScan.isEnabled = false
                binding.btnTestScan.isEnabled = false
            }
        }
    }

    private fun copyToClipboard(text: String, message: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard != null) {
            val clip = ClipData.newPlainText("HTTP-Request Debug", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
}
