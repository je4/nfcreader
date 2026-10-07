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

package ch.infoage.nfcreader.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class AppSettings(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val securePrefs: SharedPreferences = createEncryptedSharedPreferences(context)

    init {
        migratePreferences()
    }

    var targetUrl: String
        get() = prefs.getString(KEY_TARGET_URL, DEFAULT_TARGET_URL) ?: DEFAULT_TARGET_URL
        set(value) = prefs.edit().putString(KEY_TARGET_URL, value.trim()).apply()

    var httpMethod: String
        get() = prefs.getString(KEY_HTTP_METHOD, DEFAULT_HTTP_METHOD) ?: DEFAULT_HTTP_METHOD
        set(value) = prefs.edit().putString(KEY_HTTP_METHOD, value.trim()).apply()

    var jwtKey: String
        get() = securePrefs.getString(KEY_JWT_KEY, "") ?: ""
        set(value) = securePrefs.edit().putString(KEY_JWT_KEY, value.trim()).apply()

    var debugMode: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_MODE, value).apply()

    var afiPassword: String
        get() = securePrefs.getString(KEY_AFI_PASSWORD, DEFAULT_AFI_PASSWORD) ?: DEFAULT_AFI_PASSWORD
        set(value) = securePrefs.edit().putString(KEY_AFI_PASSWORD, normalizeHex32(value)).apply()

    var writePassword: String
        get() = securePrefs.getString(KEY_WRITE_PASSWORD, DEFAULT_WRITE_PASSWORD) ?: DEFAULT_WRITE_PASSWORD
        set(value) = securePrefs.edit().putString(KEY_WRITE_PASSWORD, normalizeHex32(value)).apply()

    var useAfiPassword: Boolean
        get() = prefs.getBoolean(KEY_USE_AFI_PASSWORD, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_AFI_PASSWORD, value).apply()

    var useWritePassword: Boolean
        get() = prefs.getBoolean(KEY_USE_WRITE_PASSWORD, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_WRITE_PASSWORD, value).apply()

    fun getAfiPasswordLong(): Long = parseHex32ToLong(afiPassword)

    fun getWritePasswordLong(): Long = parseHex32ToLong(writePassword)

    fun getAfiPasswordBytes(lsbFirst: Boolean = false): ByteArray = hex32ToBytes(afiPassword, lsbFirst)

    fun getWritePasswordBytes(lsbFirst: Boolean = false): ByteArray = hex32ToBytes(writePassword, lsbFirst)

    private fun migratePreferences() {
        try {
            // 1. If jwtKey was previously stored in standard prefs, migrate to securePrefs and remove from standard prefs
            if (prefs.contains(KEY_JWT_KEY)) {
                val legacyJwt = prefs.getString(KEY_JWT_KEY, null)
                if (!legacyJwt.isNullOrBlank() && !securePrefs.contains(KEY_JWT_KEY)) {
                    securePrefs.edit().putString(KEY_JWT_KEY, legacyJwt).apply()
                }
                prefs.edit().remove(KEY_JWT_KEY).apply()
            }

            // 2. If afiPassword or writePassword were previously stored in standard prefs, migrate to securePrefs
            if (prefs.contains(KEY_AFI_PASSWORD)) {
                val legacyAfi = prefs.getString(KEY_AFI_PASSWORD, null)
                if (!legacyAfi.isNullOrBlank() && !securePrefs.contains(KEY_AFI_PASSWORD)) {
                    securePrefs.edit().putString(KEY_AFI_PASSWORD, normalizeHex32(legacyAfi)).apply()
                }
                prefs.edit().remove(KEY_AFI_PASSWORD).apply()
            }
            if (prefs.contains(KEY_WRITE_PASSWORD)) {
                val legacyWrite = prefs.getString(KEY_WRITE_PASSWORD, null)
                if (!legacyWrite.isNullOrBlank() && !securePrefs.contains(KEY_WRITE_PASSWORD)) {
                    securePrefs.edit().putString(KEY_WRITE_PASSWORD, normalizeHex32(legacyWrite)).apply()
                }
                prefs.edit().remove(KEY_WRITE_PASSWORD).apply()
            }

            // 3. If targetUrl or httpMethod were stored in securePrefs, migrate back to standard prefs
            if (securePrefs.contains(KEY_TARGET_URL)) {
                val secureTargetUrl = securePrefs.getString(KEY_TARGET_URL, null)
                if (secureTargetUrl != null && !prefs.contains(KEY_TARGET_URL)) {
                    prefs.edit().putString(KEY_TARGET_URL, secureTargetUrl).apply()
                }
                securePrefs.edit().remove(KEY_TARGET_URL).apply()
            }
            if (securePrefs.contains(KEY_HTTP_METHOD)) {
                val secureHttpMethod = securePrefs.getString(KEY_HTTP_METHOD, null)
                if (secureHttpMethod != null && !prefs.contains(KEY_HTTP_METHOD)) {
                    prefs.edit().putString(KEY_HTTP_METHOD, secureHttpMethod).apply()
                }
                securePrefs.edit().remove(KEY_HTTP_METHOD).apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to migrate preferences", e)
        }
    }

    companion object {
        private const val TAG = "AppSettings"
        private const val PREFS_NAME = "nfc_reader_settings"
        private const val SECURE_PREFS_NAME = "nfc_reader_secure_settings"

        private const val KEY_TARGET_URL = "target_url"
        private const val KEY_HTTP_METHOD = "http_method"
        private const val KEY_JWT_KEY = "jwt_key"
        private const val KEY_DEBUG_MODE = "debug_mode"
        private const val KEY_AFI_PASSWORD = "afi_password"
        private const val KEY_WRITE_PASSWORD = "write_password"
        private const val KEY_USE_AFI_PASSWORD = "use_afi_password"
        private const val KEY_USE_WRITE_PASSWORD = "use_write_password"

        const val DEFAULT_TARGET_URL = "https://httpbin.org/get"
        const val DEFAULT_HTTP_METHOD = "GET"
        const val DEFAULT_AFI_PASSWORD = "00000000"
        const val DEFAULT_WRITE_PASSWORD = "00000000"

        fun normalizeHex32(hex: String): String {
            val clean = hex.trim().removePrefix("0x").removePrefix("0X").replace(" ", "").replace(":", "").uppercase()
            return if (clean.isEmpty()) DEFAULT_AFI_PASSWORD else clean.padStart(8, '0')
        }

        fun isValidHex32(hex: String): Boolean {
            val clean = hex.trim().removePrefix("0x").removePrefix("0X").replace(" ", "").replace(":", "")
            if (clean.isEmpty()) return true
            if (clean.length != 8) return false
            return clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        }

        fun parseHex32ToLong(hex: String): Long {
            val clean = hex.trim().removePrefix("0x").removePrefix("0X").replace(" ", "").replace(":", "")
            return clean.toLongOrNull(16) ?: 0L
        }

        fun hex32ToBytes(hex: String, lsbFirst: Boolean = false): ByteArray {
            val value = parseHex32ToLong(hex)
            val bytes = byteArrayOf(
                ((value ushr 24) and 0xFF).toByte(),
                ((value ushr 16) and 0xFF).toByte(),
                ((value ushr 8) and 0xFF).toByte(),
                (value and 0xFF).toByte()
            )
            return if (lsbFirst) bytes.reversedArray() else bytes
        }

        private fun createEncryptedSharedPreferences(context: Context): SharedPreferences {
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                EncryptedSharedPreferences.create(
                    context,
                    SECURE_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize EncryptedSharedPreferences, falling back to standard preferences", e)
                context.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
            }
        }
    }
}
