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

    /**
     * URL-safe Base64 encoding without padding (RFC 7515 / RFC 4648 Section 5).
     */
    fun base64UrlEncode(data: ByteArray): String {
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val sb = StringBuilder((data.size * 4 + 2) / 3)
        var i = 0
        while (i < data.size) {
            val b1 = data[i++].toInt() and 0xFF
            sb.append(table[b1 shr 2])
            if (i < data.size) {
                val b2 = data[i++].toInt() and 0xFF
                sb.append(table[((b1 and 0x03) shl 4) or (b2 shr 4)])
                if (i < data.size) {
                    val b3 = data[i++].toInt() and 0xFF
                    sb.append(table[((b2 and 0x0F) shl 2) or (b3 shr 6)])
                    sb.append(table[b3 and 0x3F])
                } else {
                    sb.append(table[(b2 and 0x0F) shl 2])
                }
            } else {
                sb.append(table[(b1 and 0x03) shl 4])
            }
        }
        return sb.toString()
    }

    /**
     * Decodes Base64Url string to ByteArray.
     */
    fun base64UrlDecode(str: String): ByteArray {
        val clean = str.trim().replace("\r", "").replace("\n", "")
        val padding = (4 - (clean.length % 4)) % 4
        val padded = clean + "=".repeat(padding)
        val standardBase64 = padded.replace('-', '+').replace('_', '/')
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        val byteList = mutableListOf<Byte>()
        var i = 0
        while (i < standardBase64.length) {
            val c1 = standardBase64[i++]
            val c2 = standardBase64[i++]
            val c3 = standardBase64[i++]
            val c4 = standardBase64[i++]

            val b1 = table.indexOf(c1)
            val b2 = table.indexOf(c2)
            val b3 = if (c3 == '=') -1 else table.indexOf(c3)
            val b4 = if (c4 == '=') -1 else table.indexOf(c4)

            if (b1 >= 0 && b2 >= 0) {
                byteList.add(((b1 shl 2) or (b2 shr 4)).toByte())
                if (b3 >= 0) {
                    byteList.add((((b2 and 0x0F) shl 4) or (b3 shr 2)).toByte())
                    if (b4 >= 0) {
                        byteList.add((((b3 and 0x03) shl 6) or b4).toByte())
                    }
                }
            }
        }
        return byteList.toByteArray()
    }
}
