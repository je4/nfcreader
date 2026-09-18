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

package ch.infoage.nfcreader.data.network

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object UrlBuilder {

    /**
     * Builds the target URL containing the user-entered text and the NFC content.
     * Supports both template placeholders ({text}, {nfc}, {uid}, {timestamp}) and
     * automatic query parameter appending if placeholders are not present.
     */
    fun buildUrl(
        baseUrlOrTemplate: String,
        userText: String,
        nfcContent: String,
        uid: String,
        timestamp: Long = System.currentTimeMillis(),
        jwtToken: String = ""
    ): String {
        val trimmed = baseUrlOrTemplate.trim()
        if (trimmed.isEmpty()) {
            return ""
        }

        val encodedText = encode(userText)
        val encodedNfc = encode(nfcContent)
        val encodedUid = encode(uid)
        val encodedTimestamp = encode(timestamp.toString())
        val encodedJwt = encode(jwtToken)

        val hasPlaceholders = trimmed.contains("{text}") ||
                trimmed.contains("{nfc}") ||
                trimmed.contains("{uid}") ||
                trimmed.contains("{timestamp}") ||
                trimmed.contains("{jwt}")

        return if (hasPlaceholders) {
            trimmed
                .replace("{text}", encodedText)
                .replace("{nfc}", encodedNfc)
                .replace("{uid}", encodedUid)
                .replace("{timestamp}", encodedTimestamp)
                .replace("{jwt}", encodedJwt)
        } else {
            val delimiter = if (trimmed.contains("?")) "&" else "?"
            "$trimmed${delimiter}text=$encodedText&nfc=$encodedNfc&uid=$encodedUid&ts=$encodedTimestamp"
        }
    }

    private fun encode(value: String): String {
        return try {
            URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        } catch (e: Exception) {
            value
        }
    }
}
