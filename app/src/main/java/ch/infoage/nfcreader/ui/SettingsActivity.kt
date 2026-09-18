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
import androidx.appcompat.app.AppCompatActivity
import ch.infoage.nfcreader.R
import ch.infoage.nfcreader.data.local.AppSettings
import ch.infoage.nfcreader.databinding.ActivitySettingsBinding

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
    }

    private fun setupToolbar() {
        binding.toolbarSettings.setNavigationOnClickListener {
            saveCurrentSettings()
            finish()
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
    }

    private fun setupListeners() {
        binding.btnSaveSettings.setOnClickListener {
            saveCurrentSettings()
            Toast.makeText(this, R.string.settings_saved_toast, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun saveCurrentSettings() {
        val targetUrl = binding.etSettingsTargetUrl.text?.toString().orEmpty().trim()
        val httpMethod = if (binding.rbSettingsPost.isChecked) "POST" else "GET"
        val jwtKey = binding.etSettingsJwtKey.text?.toString().orEmpty().trim()

        appSettings.targetUrl = if (targetUrl.isNotBlank()) targetUrl else AppSettings.DEFAULT_TARGET_URL
        appSettings.httpMethod = httpMethod
        appSettings.jwtKey = jwtKey
        setResult(RESULT_OK)
    }
}
