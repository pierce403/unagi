package com.thingalert.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.thingalert.data.SightingEntity
import com.thingalert.databinding.ItemSightingBinding
import com.thingalert.util.Formatters

class SightingAdapter : ListAdapter<SightingEntity, SightingAdapter.SightingViewHolder>(DiffCallback) {
  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SightingViewHolder {
    val binding = ItemSightingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    return SightingViewHolder(binding)
  }

  override fun onBindViewHolder(holder: SightingViewHolder, position: Int) {
    holder.bind(getItem(position))
  }

  class SightingViewHolder(private val binding: ItemSightingBinding) :
    RecyclerView.ViewHolder(binding.root) {
    fun bind(item: SightingEntity) {
      binding.sightingTimestamp.text = Formatters.formatTimestamp(item.timestamp)
      binding.sightingRssi.text = Formatters.formatRssi(item.rssi)
      val metaParts = listOfNotNull(
        item.name?.takeIf { it.isNotBlank() }?.let { "Name: $it" },
        item.address?.takeIf { it.isNotBlank() }?.let { "Addr: $it" }
      )
      binding.sightingMeta.text = if (metaParts.isEmpty()) "" else metaParts.joinToString(" • ")
    }
  }

  companion object {
    private val DiffCallback = object : DiffUtil.ItemCallback<SightingEntity>() {
      override fun areItemsTheSame(oldItem: SightingEntity, newItem: SightingEntity): Boolean {
        return oldItem.id == newItem.id
      }

      override fun areContentsTheSame(oldItem: SightingEntity, newItem: SightingEntity): Boolean {
        return oldItem == newItem
      }
    }
  }
}
