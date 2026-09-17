package com.example.nfcreader.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.nfcreader.R
import com.example.nfcreader.data.model.NfcScanResult
import com.example.nfcreader.databinding.ItemScanLogBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScanHistoryAdapter : ListAdapter<NfcScanResult, ScanHistoryAdapter.ScanViewHolder>(DiffCallback) {

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScanViewHolder {
        val binding = ItemScanLogBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ScanViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ScanViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ScanViewHolder(private val binding: ItemScanLogBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: NfcScanResult) {
            val context = binding.root.context
            binding.tvLogTagUid.text = "UID: ${item.uid} (${item.tagType})"
            binding.tvLogUserText.text = "Eingegebener Text: \"${item.userText}\""
            binding.tvLogNfcContent.text = "NFC-Wert: ${item.content}"
            binding.tvLogUrl.text = "URL: ${item.requestUrl}"
            binding.tvLogTimestamp.text = timeFormat.format(Date(item.timestamp))

            if (item.isSuccess) {
                binding.tvLogStatusBadge.text = "HTTP ${item.httpStatus ?: 200}"
                binding.tvLogStatusBadge.setBackgroundColor(
                    ContextCompat.getColor(context, R.color.status_success)
                )
                binding.tvLogResponseDetails.text = "${item.durationMs} ms"
            } else {
                binding.tvLogStatusBadge.text = "Fehler"
                binding.tvLogStatusBadge.setBackgroundColor(
                    ContextCompat.getColor(context, R.color.status_error)
                )
                binding.tvLogResponseDetails.text = item.errorMessage ?: "Fehlgeschlagen"
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<NfcScanResult>() {
        override fun areItemsTheSame(oldItem: NfcScanResult, newItem: NfcScanResult): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: NfcScanResult, newItem: NfcScanResult): Boolean {
            return oldItem == newItem
        }
    }
}
