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
