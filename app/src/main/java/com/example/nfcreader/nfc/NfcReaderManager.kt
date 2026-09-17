package com.example.nfcreader.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle

class NfcReaderManager(
    private val activity: Activity,
    private val onTagScanned: (Tag) -> Unit
) : NfcAdapter.ReaderCallback {

    private val nfcAdapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    enum class NfcAvailability {
        NOT_SUPPORTED,
        DISABLED,
        ENABLED
    }

    fun getAvailability(): NfcAvailability {
        val adapter = nfcAdapter ?: return NfcAvailability.NOT_SUPPORTED
        return if (adapter.isEnabled) NfcAvailability.ENABLED else NfcAvailability.DISABLED
    }

    fun startContinuousScanning() {
        val adapter = nfcAdapter ?: return
        if (!adapter.isEnabled) return

        val flags = NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F

        val options = Bundle().apply {
            // Presence check delay in ms
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
        }

        try {
            adapter.enableReaderMode(activity, this, flags, options)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopContinuousScanning() {
        val adapter = nfcAdapter ?: return
        try {
            adapter.disableReaderMode(activity)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onTagDiscovered(tag: Tag?) {
        if (tag != null) {
            onTagScanned(tag)
        }
    }
}
