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

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.view.View
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
            viewModel.setScanningActive(isChecked)
            if (isChecked) {
                nfcManager.startContinuousScanning()
                binding.tvNfcStatus.text = getString(R.string.nfc_status_scanning)
            } else {
                nfcManager.stopContinuousScanning()
                binding.tvNfcStatus.text = "Kontinuierlicher Scan pausiert"
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
                    viewModel.scanHistory.collectLatest { history ->
                        historyAdapter.submitList(history)
                    }
                }

                launch {
                    viewModel.lastScan.collectLatest { lastScan ->
                        if (lastScan != null) {
                            binding.tvLastScanTime.text = timeFormat.format(Date(lastScan.timestamp))
                            binding.tvLastScanDetails.text = buildString {
                                append("UID: ").append(lastScan.uid)
                                append(" (").append(lastScan.tagType).append(")\n")
                                append("Text: \"").append(lastScan.userText).append("\"\n")
                                append("NFC: ").append(lastScan.content)
                            }

                            binding.tvLastScanUrlCall.visibility = View.VISIBLE
                            if (lastScan.isSuccess) {
                                binding.tvLastScanUrlCall.text = "Aufruf erfolgreich (HTTP ${lastScan.httpStatus ?: 200}):\n${lastScan.requestUrl}"
                                binding.tvLastScanUrlCall.setTextColor(
                                    ContextCompat.getColor(this@MainActivity, R.color.status_success)
                                )
                            } else {
                                binding.tvLastScanUrlCall.text = "Aufruf fehlgeschlagen (${lastScan.errorMessage}):\n${lastScan.requestUrl}"
                                binding.tvLastScanUrlCall.setTextColor(
                                    ContextCompat.getColor(this@MainActivity, R.color.status_error)
                                )
                            }
                        } else {
                            binding.tvLastScanTime.text = ""
                            binding.tvLastScanDetails.setText(R.string.no_scans_yet)
                            binding.tvLastScanUrlCall.visibility = View.GONE
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

        updateNfcStatus()
        if (viewModel.isScanningActive.value) {
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
        when (nfcManager.getAvailability()) {
            NfcReaderManager.NfcAvailability.ENABLED -> {
                binding.tvNfcStatus.text = if (viewModel.isScanningActive.value) {
                    getString(R.string.nfc_status_scanning)
                } else {
                    "Kontinuierlicher Scan pausiert"
                }
                binding.switchContinuousScan.isEnabled = true
            }
            NfcReaderManager.NfcAvailability.DISABLED -> {
                binding.tvNfcStatus.text = getString(R.string.nfc_status_disabled)
                binding.switchContinuousScan.isEnabled = false
            }
            NfcReaderManager.NfcAvailability.NOT_SUPPORTED -> {
                binding.tvNfcStatus.text = getString(R.string.nfc_status_unsupported)
                binding.switchContinuousScan.isEnabled = false
            }
        }
    }
}
