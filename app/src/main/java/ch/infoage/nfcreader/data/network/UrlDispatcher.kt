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

import ch.infoage.nfcreader.data.model.ScanResponse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class UrlDispatcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    suspend fun dispatchScan(
        targetUrlTemplate: String,
        userText: String,
        nfcContent: String,
        uid: String,
        httpMethod: String = "GET",
        jwtToken: String? = null
    ): Result<ScanResponse> = withContext(dispatcher) {
        val startTime = System.currentTimeMillis()
        try {
            val fullUrl = UrlBuilder.buildUrl(
                baseUrlOrTemplate = targetUrlTemplate,
                userText = userText,
                nfcContent = nfcContent,
                uid = uid,
                timestamp = startTime,
                jwtToken = jwtToken.orEmpty()
            )

            if (fullUrl.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("URL darf nicht leer sein"))
            }

            val requestBuilder = Request.Builder().url(fullUrl)

            if (httpMethod.equals("POST", ignoreCase = true)) {
                val jsonBody = JSONObject().apply {
                    put("text", userText)
                    put("nfc", nfcContent)
                    put("uid", uid)
                    put("timestamp", startTime)
                    if (!jwtToken.isNullOrBlank()) {
                        put("jwt", jwtToken)
                    }
                }.toString()

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = jsonBody.toRequestBody(mediaType)
                requestBuilder.post(requestBody)
            } else {
                requestBuilder.get()
            }

            requestBuilder.header("User-Agent", "Iso15693NfcReader/1.0 (Android)")
            requestBuilder.header("Accept", "application/json, text/plain, */*")

            if (!jwtToken.isNullOrBlank()) {
                val authHeader = if (jwtToken.startsWith("Bearer ", ignoreCase = true)) {
                    jwtToken
                } else {
                    "Bearer $jwtToken"
                }
                requestBuilder.header("Authorization", authHeader)
            }

            val response = client.newCall(requestBuilder.build()).execute()
            val durationMs = System.currentTimeMillis() - startTime
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                Result.success(
                    ScanResponse(
                        httpStatus = response.code,
                        responseBody = responseBody,
                        requestUrl = fullUrl,
                        durationMs = durationMs
                    )
                )
            } else {
                Result.failure(
                    IOException("HTTP ${response.code}: ${response.message}\n$responseBody")
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
