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

        // Setup RecyclerView
        binding.rvScanHistory.layoutManager = LinearLayoutManager(this)
        binding.rvScanHistory.adapter = historyAdapter

        // User text input binding
        binding.etUserText.doAfterTextChanged { editable ->
            viewModel.setUserText(editable?.toString().orEmpty())
        }

        // Continuous scan toggle
        binding.switchContinuousScan.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && viewModel.userText.value.isEmpty()) {
                binding.switchContinuousScan.isChecked = false
                return@setOnCheckedChangeListener
            }
            viewModel.setScanningActive(isChecked)
            if (isChecked) {
                nfcManager.startContinuousScanning()
                binding.tvNfcStatus.text = getString(R.string.nfc_status_scanning)
            } else {
                nfcManager.stopContinuousScanning()
                updateNfcStatus()
            }
        }

        // Manual test scan trigger
        binding.btnTestScan.setOnClickListener {
            viewModel.triggerTestScan()
        }

        // Clear history
        binding.tvClearHistory.setOnClickListener {
            viewModel.clearHistory()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.userText.collectLatest { text ->
                        val hasLocation = text.isNotEmpty()
                        if (!hasLocation && binding.switchContinuousScan.isChecked) {
                            binding.switchContinuousScan.isChecked = false
                        }
                        updateNfcStatus()
                    }
                }

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
                                            "07" -> "07 (Ausgeliehen)"
                                            "C7" -> "C7 (Gesichert)"
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
        if (viewModel.isScanningActive.value && viewModel.userText.value.isNotEmpty()) {
            nfcManager.startContinuousScanning()
        }
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
