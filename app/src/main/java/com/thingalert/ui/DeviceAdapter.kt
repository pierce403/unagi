package com.thingalert.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.thingalert.databinding.ItemDeviceBinding
import com.thingalert.util.Formatters

class DeviceAdapter(
  private val onClick: (DeviceListItem) -> Unit
) : ListAdapter<DeviceListItem, DeviceAdapter.DeviceViewHolder>(DiffCallback) {

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
    val binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    return DeviceViewHolder(binding, onClick)
  }

  override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
    holder.bind(getItem(position))
  }

  class DeviceViewHolder(
    private val binding: ItemDeviceBinding,
    private val onClick: (DeviceListItem) -> Unit
  ) : RecyclerView.ViewHolder(binding.root) {
    fun bind(item: DeviceListItem) {
      binding.deviceName.text = item.displayTitle
      binding.deviceMeta.text = item.metaLine
      binding.deviceRssi.text = Formatters.formatRssi(item.lastRssi)
      binding.root.setOnClickListener { onClick(item) }
    }
  }

  companion object {
    private val DiffCallback = object : DiffUtil.ItemCallback<DeviceListItem>() {
      override fun areItemsTheSame(oldItem: DeviceListItem, newItem: DeviceListItem): Boolean {
        return oldItem.deviceKey == newItem.deviceKey
      }

      override fun areContentsTheSame(oldItem: DeviceListItem, newItem: DeviceListItem): Boolean {
        return oldItem == newItem
      }
    }
  }
}
