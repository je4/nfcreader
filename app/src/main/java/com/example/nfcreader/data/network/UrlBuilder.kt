package com.example.nfcreader.data.network

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
