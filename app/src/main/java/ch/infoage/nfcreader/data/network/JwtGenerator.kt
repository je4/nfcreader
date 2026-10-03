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

import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

object JwtGenerator {

    private const val ALGORITHM_HMAC_SHA256 = "HmacSHA256"
    const val DEFAULT_VALIDITY_SECONDS: Long = 60L

    /**
     * Generates a signed JWT token using the HS256 algorithm.
     *
     * @param secret Secret key used for signing (HMAC-SHA256)
     * @param validitySeconds Token validity duration in seconds (defaults to 60 seconds / 1 minute)
     * @param issuedAtMillis Timestamp in milliseconds when the token is issued (defaults to current time)
     * @param customClaims Optional map of additional claims to include in the payload
     * @return Formatted JWT string: header.payload.signature
     */
    fun generateToken(
        secret: String,
        validitySeconds: Long = DEFAULT_VALIDITY_SECONDS,
        issuedAtMillis: Long = System.currentTimeMillis(),
        customClaims: Map<String, Any> = emptyMap()
    ): String {
        require(secret.isNotBlank()) { "Secret key must not be blank" }

        val headerJson = JSONObject().apply {
            put("alg", "HS256")
            put("typ", "JWT")
        }

        val iatSeconds = issuedAtMillis / 1000
        val expSeconds = iatSeconds + validitySeconds

        val payloadJson = JSONObject().apply {
            put("iat", iatSeconds)
            put("exp", expSeconds)
            for ((key, value) in customClaims) {
                put(key, value)
            }
        }

        val encodedHeader = base64UrlEncode(headerJson.toString().toByteArray(Charsets.UTF_8))
        val encodedPayload = base64UrlEncode(payloadJson.toString().toByteArray(Charsets.UTF_8))

        val contentToSign = "$encodedHeader.$encodedPayload"
        val signature = signHmacSha256(contentToSign, secret)
        val encodedSignature = base64UrlEncode(signature)

        return "$contentToSign.$encodedSignature"
    }

    private fun signHmacSha256(data: String, secret: String): ByteArray {
        val mac = Mac.getInstance(ALGORITHM_HMAC_SHA256)
        val secretKeySpec = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), ALGORITHM_HMAC_SHA256)
        mac.init(secretKeySpec)
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    /**
     * Verifies the signature of a JWT token.
     */
    fun verifySignature(token: String, secret: String): Boolean {
        val parts = token.split(".")
        if (parts.size != 3) return false
        val contentToSign = "${parts[0]}.${parts[1]}"
        val expectedSignature = base64UrlEncode(signHmacSha256(contentToSign, secret))
        return parts[2] == expectedSignature
    }

    /**
     * Decodes the payload JSON from a JWT token.
     */
    fun parsePayload(token: String): JSONObject? {
        val parts = token.split(".")
        if (parts.size != 3) return null
        return try {
            val payloadBytes = base64UrlDecode(parts[1])
            JSONObject(String(payloadBytes, Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private val base64UrlFormat = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    /**
     * URL-safe Base64 encoding without padding (RFC 7515 / RFC 4648 Section 5).
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun base64UrlEncode(data: ByteArray): String =
        base64UrlFormat.encode(data)

    /**
     * Decodes Base64Url string to ByteArray.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun base64UrlDecode(str: String): ByteArray =
        base64UrlFormat.decode(str.trim())
}
