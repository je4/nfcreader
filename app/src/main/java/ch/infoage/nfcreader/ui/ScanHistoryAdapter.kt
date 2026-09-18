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

package ch.infoage.nfcreader.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ch.infoage.nfcreader.R
import ch.infoage.nfcreader.data.model.NfcScanResult
import ch.infoage.nfcreader.databinding.ItemScanLogBinding
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
