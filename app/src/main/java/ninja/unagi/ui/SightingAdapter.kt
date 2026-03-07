package ninja.unagi.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ninja.unagi.data.SightingEntity
import ninja.unagi.databinding.ItemSightingBinding
import ninja.unagi.util.BleMetadataInterpreter
import ninja.unagi.util.BluetoothAssignedNumbersRegistry
import ninja.unagi.util.Formatters
import ninja.unagi.util.ObservationMetadataParser

class SightingAdapter(
  private val assignedNumbers: BluetoothAssignedNumbersRegistry
) : ListAdapter<SightingEntity, SightingAdapter.SightingViewHolder>(DiffCallback) {
  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SightingViewHolder {
    val binding = ItemSightingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    return SightingViewHolder(binding, assignedNumbers)
  }

  override fun onBindViewHolder(holder: SightingViewHolder, position: Int) {
    holder.bind(getItem(position))
  }

  class SightingViewHolder(
    private val binding: ItemSightingBinding,
    private val assignedNumbers: BluetoothAssignedNumbersRegistry
  ) :
    RecyclerView.ViewHolder(binding.root) {
    fun bind(item: SightingEntity) {
      binding.sightingTimestamp.text = Formatters.formatTimestamp(item.timestamp)
      binding.sightingRssi.text = Formatters.formatRssi(item.rssi)
      val metadataSummary = BleMetadataInterpreter.summarize(
        ObservationMetadataParser.parse(item.metadataJson),
        assignedNumbers
      )
      val metaParts = buildList {
        item.name?.takeIf { it.isNotBlank() }?.let { add("Name: $it") }
        item.address?.takeIf { it.isNotBlank() }?.let { add("Addr: $it") }
        addAll(metadataSummary.listLabels.take(2))
      }
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
