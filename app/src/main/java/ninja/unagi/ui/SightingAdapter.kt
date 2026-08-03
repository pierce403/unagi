package ninja.unagi.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ninja.unagi.databinding.ItemSightingBinding

class SightingAdapter : ListAdapter<SightingListItem, SightingAdapter.SightingViewHolder>(DiffCallback) {
  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SightingViewHolder {
    val binding = ItemSightingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    return SightingViewHolder(binding)
  }

  override fun onBindViewHolder(holder: SightingViewHolder, position: Int) {
    holder.bind(getItem(position))
  }

  class SightingViewHolder(
    private val binding: ItemSightingBinding
  ) : RecyclerView.ViewHolder(binding.root) {
    fun bind(item: SightingListItem) {
      binding.sightingTimestamp.text = item.timestampText
      binding.sightingRssi.text = item.rssiText
      binding.sightingMeta.text = item.metaText
    }
  }

  companion object {
    private val DiffCallback = object : DiffUtil.ItemCallback<SightingListItem>() {
      override fun areItemsTheSame(oldItem: SightingListItem, newItem: SightingListItem): Boolean {
        return oldItem.id == newItem.id
      }

      override fun areContentsTheSame(oldItem: SightingListItem, newItem: SightingListItem): Boolean {
        return oldItem == newItem
      }
    }
  }
}
