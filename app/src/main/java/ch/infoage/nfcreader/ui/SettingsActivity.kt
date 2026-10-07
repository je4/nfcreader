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

import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import ch.infoage.nfcreader.R
import ch.infoage.nfcreader.data.local.AppSettings
import ch.infoage.nfcreader.databinding.ActivitySettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var appSettings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appSettings = AppSettings(this)

        setupToolbar()
        loadSettings()
        setupListeners()
        setupBackHandler()
    }

    private fun setupToolbar() {
        binding.toolbarSettings.setNavigationOnClickListener {
            handleExit()
        }
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleExit()
            }
        })
    }

    private fun handleExit() {
        if (saveCurrentSettings()) {
            Toast.makeText(this, R.string.settings_saved_toast, Toast.LENGTH_SHORT).show()
            finish()
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_invalid_dialog_title)
                .setMessage(R.string.settings_invalid_dialog_message)
                .setPositiveButton(R.string.settings_invalid_dialog_discard) { _, _ ->
                    finish()
                }
                .setNegativeButton(R.string.settings_invalid_dialog_edit, null)
                .show()
        }
    }

    private fun loadSettings() {
        binding.etSettingsTargetUrl.setText(appSettings.targetUrl)
        if (appSettings.httpMethod.equals("POST", ignoreCase = true)) {
            binding.rbSettingsPost.isChecked = true
        } else {
            binding.rbSettingsGet.isChecked = true
        }
        binding.etSettingsJwtKey.setText(appSettings.jwtKey)
        binding.switchSettingsUseAfiPassword.isChecked = appSettings.useAfiPassword
        binding.etSettingsAfiPassword.setText(appSettings.afiPassword)
        binding.switchSettingsUseWritePassword.isChecked = appSettings.useWritePassword
        binding.switchSettingsUseWritePassword.isEnabled = false
        binding.tilSettingsWritePassword.isEnabled = false
        binding.etSettingsWritePassword.setText(appSettings.writePassword)
        binding.etSettingsWritePassword.isEnabled = false
        binding.switchSettingsDebugMode.isChecked = appSettings.debugMode
    }

    private fun setupListeners() {
        binding.btnSaveSettings.setOnClickListener {
            if (saveCurrentSettings()) {
                Toast.makeText(this, R.string.settings_saved_toast, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun saveCurrentSettings(): Boolean {
        val afiPasswordInput = binding.etSettingsAfiPassword.text?.toString().orEmpty().trim()
        val writePasswordInput = binding.etSettingsWritePassword.text?.toString().orEmpty().trim()

        var hasError = false
        if (!AppSettings.isValidHex32(afiPasswordInput)) {
            binding.tilSettingsAfiPassword.error = getString(R.string.settings_password_invalid_hex)
            hasError = true
        } else {
            binding.tilSettingsAfiPassword.error = null
        }

        if (binding.etSettingsWritePassword.isEnabled && !AppSettings.isValidHex32(writePasswordInput)) {
            binding.tilSettingsWritePassword.error = getString(R.string.settings_password_invalid_hex)
            hasError = true
        } else {
            binding.tilSettingsWritePassword.error = null
        }

        if (hasError) {
            return false
        }

        val targetUrl = binding.etSettingsTargetUrl.text?.toString().orEmpty().trim()
        val httpMethod = if (binding.rbSettingsPost.isChecked) "POST" else "GET"
        val jwtKey = binding.etSettingsJwtKey.text?.toString().orEmpty().trim()
        val debugMode = binding.switchSettingsDebugMode.isChecked

        appSettings.targetUrl = if (targetUrl.isNotBlank()) targetUrl else AppSettings.DEFAULT_TARGET_URL
        appSettings.httpMethod = httpMethod
        appSettings.jwtKey = jwtKey
        appSettings.useAfiPassword = binding.switchSettingsUseAfiPassword.isChecked
        appSettings.afiPassword = afiPasswordInput
        if (binding.switchSettingsUseWritePassword.isEnabled) {
            appSettings.useWritePassword = binding.switchSettingsUseWritePassword.isChecked
        }
        if (binding.etSettingsWritePassword.isEnabled) {
            appSettings.writePassword = writePasswordInput
        }
        appSettings.debugMode = debugMode
        setResult(RESULT_OK)
        return true
    }
}
