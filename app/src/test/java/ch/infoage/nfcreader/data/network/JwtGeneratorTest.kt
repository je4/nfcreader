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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JwtGeneratorTest {

    @Test
    fun testGenerateTokenStructureAndAlgorithm() {
        val secretKey = "my-secure-secret-key"
        val fixedTimeMillis = 1700000000000L // 1700000000 seconds
        val token = JwtGenerator.generateToken(
            secret = secretKey,
            validitySeconds = 60,
            issuedAtMillis = fixedTimeMillis
        )

        assertNotNull(token)
        val parts = token.split(".")
        assertEquals(3, parts.size)

        // Verify header
        val headerJson = JSONObject(String(JwtGenerator.base64UrlDecode(parts[0]), Charsets.UTF_8))
        assertEquals("HS256", headerJson.getString("alg"))
        assertEquals("JWT", headerJson.getString("typ"))

        // Verify payload
        val payloadJson = JSONObject(String(JwtGenerator.base64UrlDecode(parts[1]), Charsets.UTF_8))
        assertEquals(1700000000L, payloadJson.getLong("iat"))
        assertEquals(1700000060L, payloadJson.getLong("exp"))
        assertEquals(60L, payloadJson.getLong("exp") - payloadJson.getLong("iat"))

        // Verify signature
        assertTrue(JwtGenerator.verifySignature(token, secretKey))
        assertFalse(JwtGenerator.verifySignature(token, "wrong-key"))
    }

    @Test
    fun testValidityIsOneMinuteByDefault() {
        val secretKey = "default-validity-key"
        val nowMillis = System.currentTimeMillis()
        val token = JwtGenerator.generateToken(secret = secretKey, issuedAtMillis = nowMillis)

        val payload = JwtGenerator.parsePayload(token)
        assertNotNull(payload)

        val iat = payload!!.getLong("iat")
        val exp = payload.getLong("exp")
        assertEquals(60L, exp - iat)
    }

    @Test
    fun testTokenWithCustomClaims() {
        val secretKey = "custom-claims-key"
        val customClaims = mapOf(
            "sub" to "nfc-scanner",
            "uid" to "E004015012345678"
        )
        val token = JwtGenerator.generateToken(
            secret = secretKey,
            customClaims = customClaims
        )

        val payload = JwtGenerator.parsePayload(token)
        assertNotNull(payload)
        assertEquals("nfc-scanner", payload?.getString("sub"))
        assertEquals("E004015012345678", payload?.getString("uid"))
        assertTrue(JwtGenerator.verifySignature(token, secretKey))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testBlankSecretThrowsException() {
        JwtGenerator.generateToken(secret = "   ")
    }

    @Test
    fun testBase64UrlEncodingAndDecoding() {
        val sample = "Hello, ISO 15693 NFC Reader! ??++//"
        val encoded = JwtGenerator.base64UrlEncode(sample.toByteArray(Charsets.UTF_8))
        assertFalse(encoded.contains("+"))
        assertFalse(encoded.contains("/"))
        assertFalse(encoded.contains("="))

        val decoded = String(JwtGenerator.base64UrlDecode(encoded), Charsets.UTF_8)
        assertEquals(sample, decoded)
    }
}
