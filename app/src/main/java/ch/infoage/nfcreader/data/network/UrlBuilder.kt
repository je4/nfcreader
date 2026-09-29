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

import ch.infoage.nfcreader.nfc.FinnishLibraryData
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object UrlBuilder {

    /**
     * Builds the target URL containing the user-entered text and the NFC content.
     * Supports both template placeholders ({text}, {raw}, {uid}, {timestamp}, {jwt},
     * {itemid}, {afi}, {country}, {isil}, {parts}, {partno}, {usagetype}, {version}) and
     * automatic query parameter appending if placeholders are not present.
     */
    fun buildUrl(
        baseUrlOrTemplate: String,
        userText: String,
        rawPayloadHex: String = "",
        uid: String,
        timestamp: Long = System.currentTimeMillis(),
        jwtToken: String = "",
        libraryData: FinnishLibraryData? = null,
        nfcContent: String = ""
    ): String {
        val trimmed = baseUrlOrTemplate.trim()
        if (trimmed.isEmpty()) {
            return ""
        }

        val rawValue = rawPayloadHex.ifBlank { nfcContent }
        val encodedText = encode(userText)
        val encodedRaw = encode(rawValue)
        val encodedUid = encode(uid)
        val encodedTimestamp = encode(timestamp.toString())
        val encodedJwt = encode(jwtToken)
        val encodedItemId = encode(libraryData?.itemId.orEmpty())
        val encodedAfi = encode(libraryData?.afi.orEmpty())
        val encodedCountry = encode(libraryData?.country.orEmpty())
        val encodedIsil = encode(libraryData?.isil.orEmpty())
        val encodedParts = encode(libraryData?.parts?.toString().orEmpty())
        val encodedPartNo = encode(libraryData?.partNo?.toString().orEmpty())
        val encodedUsageType = encode(libraryData?.usageType?.toString().orEmpty())
        val encodedVersion = encode(libraryData?.version?.toString().orEmpty())

        val hasPlaceholders = trimmed.contains("{text}") ||
                trimmed.contains("{raw}") ||
                trimmed.contains("{uid}") ||
                trimmed.contains("{timestamp}") ||
                trimmed.contains("{jwt}") ||
                trimmed.contains("{itemid}") ||
                trimmed.contains("{afi}") ||
                trimmed.contains("{country}") ||
                trimmed.contains("{isil}") ||
                trimmed.contains("{parts}") ||
                trimmed.contains("{partno}") ||
                trimmed.contains("{usagetype}") ||
                trimmed.contains("{version}")

        return if (hasPlaceholders) {
            trimmed
                .replace("{text}", encodedText)
                .replace("{raw}", encodedRaw)
                .replace("{uid}", encodedUid)
                .replace("{timestamp}", encodedTimestamp)
                .replace("{jwt}", encodedJwt)
                .replace("{itemid}", encodedItemId)
                .replace("{afi}", encodedAfi)
                .replace("{country}", encodedCountry)
                .replace("{isil}", encodedIsil)
                .replace("{parts}", encodedParts)
                .replace("{partno}", encodedPartNo)
                .replace("{usagetype}", encodedUsageType)
                .replace("{version}", encodedVersion)
        } else {
            val delimiter = if (trimmed.contains("?")) "&" else "?"
            val baseParams = "$trimmed${delimiter}text=$encodedText&raw=$encodedRaw&uid=$encodedUid&ts=$encodedTimestamp"
            if (libraryData != null && !libraryData.isTagEmpty) {
                val afiParam = if (encodedAfi.isNotBlank()) "&afi=$encodedAfi" else ""
                "$baseParams&itemid=$encodedItemId&country=$encodedCountry&isil=$encodedIsil&parts=$encodedParts&partno=$encodedPartNo&usagetype=$encodedUsageType&version=$encodedVersion$afiParam"
            } else {
                baseParams
            }
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
