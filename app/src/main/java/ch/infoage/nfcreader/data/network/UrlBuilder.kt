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

import android.os.Build
import ch.infoage.nfcreader.nfc.FinnishLibraryData
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object UrlBuilder {

    /**
     * Builds the target URL containing the marker, session, and NFC content.
     * Supports both template placeholders ({marker}, {session}, {text}, {raw}, {uid}, {timestamp}, {ts}, {jwt},
     * {itemid}, {afi}, {country}, {isil}, {parts}, {partno}, {usagetype}, {version}) and
     * automatic query parameter appending if placeholders are not present (for GET requests).
     * For POST requests, query parameters are omitted.
     */
    fun buildUrl(
        baseUrlOrTemplate: String,
        marker: String = "",
        rawPayloadHex: String = "",
        uid: String = "",
        timestamp: Long = System.currentTimeMillis(),
        jwtToken: String = "",
        libraryData: FinnishLibraryData? = null,
        nfcContent: String = "",
        session: String = "",
        deviceName: String = "",
        httpMethod: String = "GET",
        userText: String = ""
    ): String {
        val trimmed = baseUrlOrTemplate.trim()
        if (trimmed.isEmpty()) {
            return ""
        }

        val effectiveMarker = marker.ifBlank { userText }
        val rawValue = rawPayloadHex.ifBlank { nfcContent }
        val actualSession = session.ifBlank { generateSession(deviceName, timestamp) }

        val encodedMarker = encode(effectiveMarker)
        val encodedSession = encode(actualSession)
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

        if (httpMethod.equals("POST", ignoreCase = true)) {
            // Beim POST-Aufruf sollen keine GET-Parameter vorhanden sein
            val pathPart = trimmed.substringBefore('?')
            return pathPart
                .replace("{marker}", encodedMarker)
                .replace("{text}", encodedMarker)
                .replace("{session}", encodedSession)
                .replace("{raw}", encodedRaw)
                .replace("{uid}", encodedUid)
                .replace("{timestamp}", encodedTimestamp)
                .replace("{ts}", encodedTimestamp)
                .replace("{jwt}", encodedJwt)
                .replace("{itemid}", encodedItemId)
                .replace("{afi}", encodedAfi)
                .replace("{country}", encodedCountry)
                .replace("{isil}", encodedIsil)
                .replace("{parts}", encodedParts)
                .replace("{partno}", encodedPartNo)
                .replace("{usagetype}", encodedUsageType)
                .replace("{version}", encodedVersion)
        }

        val hasPlaceholders = trimmed.contains("{marker}") ||
                trimmed.contains("{text}") ||
                trimmed.contains("{session}") ||
                trimmed.contains("{raw}") ||
                trimmed.contains("{uid}") ||
                trimmed.contains("{timestamp}") ||
                trimmed.contains("{ts}") ||
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
                .replace("{marker}", encodedMarker)
                .replace("{text}", encodedMarker)
                .replace("{session}", encodedSession)
                .replace("{raw}", encodedRaw)
                .replace("{uid}", encodedUid)
                .replace("{timestamp}", encodedTimestamp)
                .replace("{ts}", encodedTimestamp)
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
            val baseParams = "$trimmed${delimiter}marker=$encodedMarker&session=$encodedSession&raw=$encodedRaw&uid=$encodedUid&ts=$encodedTimestamp"
            if (libraryData != null && !libraryData.isTagEmpty) {
                val afiParam = if (encodedAfi.isNotBlank()) "&afi=$encodedAfi" else ""
                "$baseParams&itemid=$encodedItemId&country=$encodedCountry&isil=$encodedIsil&parts=$encodedParts&partno=$encodedPartNo&usagetype=$encodedUsageType&version=$encodedVersion$afiParam"
            } else {
                baseParams
            }
        }
    }

    /**
     * Erzeugt den Session-Parameter bestehend aus Gerätename und aktuellem Datum im Format yyyyMMdd.
     */
    fun generateSession(deviceName: String = getDeviceName(), timestamp: Long = System.currentTimeMillis()): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
        val dateStr = dateFormat.format(Date(timestamp))
        val device = deviceName.ifBlank { getDeviceName() }.trim()
        return if (device.isNotBlank()) {
            "${device}_$dateStr"
        } else {
            dateStr
        }
    }

    /**
     * Ermittelt den Gerätenamen aus android.os.Build.
     */
    fun getDeviceName(): String {
        return try {
            Build.MODEL?.trim().orEmpty()
        } catch (e: Throwable) {
            ""
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
