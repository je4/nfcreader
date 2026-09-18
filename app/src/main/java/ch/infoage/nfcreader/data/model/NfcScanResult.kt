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

package ch.infoage.nfcreader.data.model

data class NfcScanResult(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val uid: String,
    val tagType: String = "ISO 15693 (NfcV)",
    val content: String,
    val userText: String,
    val requestUrl: String,
    val httpMethod: String = "GET",
    val httpStatus: Int? = null,
    val responseBody: String? = null,
    val errorMessage: String? = null,
    val isSuccess: Boolean = false,
    val durationMs: Long = 0
)

data class ScanResponse(
    val httpStatus: Int,
    val responseBody: String,
    val requestUrl: String,
    val durationMs: Long
)
