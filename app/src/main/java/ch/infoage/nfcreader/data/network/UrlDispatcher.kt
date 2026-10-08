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
import ch.infoage.nfcreader.nfc.FinnishLibraryData
import ch.infoage.nfcreader.util.AppLogger
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

    data class PreparedRequest(
        val request: Request,
        val fullUrl: String,
        val httpMethod: String,
        val headers: Map<String, String>,
        val body: String?,
        val debugString: String
    )

    fun prepareRequest(
        targetUrlTemplate: String,
        marker: String = "",
        userText: String = "",
        nfcContent: String = "",
        rawPayloadHex: String = "",
        uid: String,
        httpMethod: String = "GET",
        jwtToken: String? = null,
        jwtKey: String? = null,
        libraryData: FinnishLibraryData? = null,
        session: String = "",
        deviceName: String = "",
        timestamp: Long = System.currentTimeMillis()
    ): PreparedRequest {
        val actualMarker = marker.ifBlank { userText }
        val actualSession = session.ifBlank { UrlBuilder.generateSession(deviceName, timestamp) }

        val fullUrl = UrlBuilder.buildUrl(
            baseUrlOrTemplate = targetUrlTemplate,
            marker = actualMarker,
            rawPayloadHex = rawPayloadHex,
            uid = uid,
            timestamp = timestamp,
            jwtToken = jwtToken.orEmpty(),
            libraryData = libraryData,
            nfcContent = nfcContent,
            session = actualSession,
            deviceName = deviceName,
            httpMethod = httpMethod
        )

        if (fullUrl.isBlank()) {
            throw IllegalArgumentException("URL darf nicht leer sein")
        }

        val requestBuilder = Request.Builder().url(fullUrl)
        var outgoingBody: String? = null

        if (httpMethod.equals("POST", ignoreCase = true)) {
            val jsonBody = JSONObject().apply {
                put("marker", actualMarker)
                put("session", actualSession)
                put("raw", rawPayloadHex.ifBlank { nfcContent })
                put("uid", uid)
                put("timestamp", timestamp)
                if (!jwtToken.isNullOrBlank()) {
                    put("jwt", jwtToken)
                }
                if (libraryData != null && !libraryData.isTagEmpty) {
                    put("itemId", libraryData.itemId)
                    if (libraryData.afi.isNotBlank()) {
                        put("afi", libraryData.afi)
                    }
                    put("country", libraryData.country)
                    put("isil", libraryData.isil)
                    put("parts", libraryData.parts)
                    put("partNo", libraryData.partNo)
                    put("usageType", libraryData.usageType)
                    put("version", libraryData.version)
                    put("isCrcValid", libraryData.isCrcValid)
                }
            }.toString()

            outgoingBody = jsonBody
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

        val request = requestBuilder.build()
        val headersMap = mutableMapOf<String, String>()
        for (i in 0 until request.headers.size) {
            headersMap[request.headers.name(i)] = request.headers.value(i)
        }
        if (request.body != null && !headersMap.containsKey("Content-Type")) {
            request.body?.contentType()?.let {
                headersMap["Content-Type"] = it.toString()
            }
        }

        val debugString = formatHttpRequestDebug(
            method = request.method,
            url = fullUrl,
            headers = headersMap,
            body = outgoingBody
        )

        return PreparedRequest(
            request = request,
            fullUrl = fullUrl,
            httpMethod = request.method,
            headers = headersMap,
            body = outgoingBody,
            debugString = debugString
        )
    }

    suspend fun dispatchScan(
        targetUrlTemplate: String,
        marker: String = "",
        userText: String = "",
        nfcContent: String = "",
        rawPayloadHex: String = "",
        uid: String,
        httpMethod: String = "GET",
        jwtToken: String? = null,
        jwtKey: String? = null,
        libraryData: FinnishLibraryData? = null,
        session: String = "",
        deviceName: String = ""
    ): Result<ScanResponse> = withContext(dispatcher) {
        val startTime = System.currentTimeMillis()
        try {
            val prepared = prepareRequest(
                targetUrlTemplate = targetUrlTemplate,
                marker = marker,
                userText = userText,
                nfcContent = nfcContent,
                rawPayloadHex = rawPayloadHex,
                uid = uid,
                httpMethod = httpMethod,
                jwtToken = jwtToken,
                jwtKey = jwtKey,
                libraryData = libraryData,
                session = session,
                deviceName = deviceName,
                timestamp = startTime
            )

            AppLogger.i(TAG, "Dispatching HTTP ${prepared.httpMethod} -> ${prepared.fullUrl}")
            val response = client.newCall(prepared.request).execute()
            val durationMs = System.currentTimeMillis() - startTime
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                AppLogger.i(TAG, "HTTP Response: ${response.code} ${response.message} (${durationMs}ms)")
                Result.success(
                    ScanResponse(
                        httpStatus = response.code,
                        responseBody = responseBody,
                        requestUrl = prepared.fullUrl,
                        durationMs = durationMs,
                        jwtKey = jwtKey,
                        requestHeaders = prepared.headers,
                        requestBody = prepared.body,
                        httpRequestDebug = prepared.debugString
                    )
                )
            } else {
                AppLogger.w(TAG, "HTTP Error: ${response.code} ${response.message} (${durationMs}ms)")
                Result.failure(
                    IOException("HTTP ${response.code}: ${response.message}\n$responseBody")
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "HTTP Request execution failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "UrlDispatcher"

        fun formatHttpRequestDebug(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?
        ): String {
            val sb = StringBuilder()
            sb.append("URL: ").append(url).append("\n")
            sb.append("Method: ").append(method).append("\n")
            sb.append("Header:\n")
            if (headers.isEmpty()) {
                sb.append("  (keine)\n")
            } else {
                for ((name, value) in headers) {
                    sb.append("  ").append(name).append(": ").append(value).append("\n")
                }
            }
            sb.append("Body: ").append(if (!body.isNullOrBlank()) body else "(kein Body)")
            return sb.toString()
        }
    }
}
