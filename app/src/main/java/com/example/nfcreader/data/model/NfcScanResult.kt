package com.example.nfcreader.data.model

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
