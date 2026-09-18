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

class AppSettings(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var targetUrl: String
        get() = prefs.getString(KEY_TARGET_URL, DEFAULT_TARGET_URL) ?: DEFAULT_TARGET_URL
        set(value) = prefs.edit().putString(KEY_TARGET_URL, value.trim()).apply()

    var httpMethod: String
        get() = prefs.getString(KEY_HTTP_METHOD, DEFAULT_HTTP_METHOD) ?: DEFAULT_HTTP_METHOD
        set(value) = prefs.edit().putString(KEY_HTTP_METHOD, value.trim()).apply()

    var jwtKey: String
        get() = prefs.getString(KEY_JWT_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_JWT_KEY, value.trim()).apply()

    companion object {
        private const val PREFS_NAME = "nfc_reader_settings"
        private const val KEY_TARGET_URL = "target_url"
        private const val KEY_HTTP_METHOD = "http_method"
        private const val KEY_JWT_KEY = "jwt_key"

        const val DEFAULT_TARGET_URL = "https://httpbin.org/get"
        const val DEFAULT_HTTP_METHOD = "GET"
    }
}
