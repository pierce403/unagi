package ninja.unagi.ui

import android.text.TextUtils
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ninja.unagi.databinding.ItemDeviceBinding
import ninja.unagi.util.Formatters

class DeviceAdapter(
  private val onClick: (DeviceListItem) -> Unit,
  private val onLongClick: (DeviceListItem) -> Unit,
  private val onStarToggle: (DeviceListItem, Boolean) -> Unit
) : ListAdapter<DeviceListItem, DeviceAdapter.DeviceViewHolder>(DiffCallback) {
  private var compactMode = false

  init {
    setHasStableIds(true)
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
    val binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    return DeviceViewHolder(binding, onClick, onLongClick, onStarToggle)
  }

  override fun getItemId(position: Int): Long {
    return getItem(position).deviceKey.hashCode().toLong()
  }

  override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
    holder.bind(getItem(position), compactMode)
  }

  fun setCompactMode(enabled: Boolean) {
    if (compactMode == enabled) {
      return
    }
    compactMode = enabled
    notifyDataSetChanged()
  }

  class DeviceViewHolder(
    private val binding: ItemDeviceBinding,
    private val onClick: (DeviceListItem) -> Unit,
    private val onLongClick: (DeviceListItem) -> Unit,
    private val onStarToggle: (DeviceListItem, Boolean) -> Unit
  ) : RecyclerView.ViewHolder(binding.root) {
    fun bind(item: DeviceListItem, compactMode: Boolean) {
      applyCardDensity(compactMode)
      binding.deviceName.text = item.displayTitle
      binding.deviceName.maxLines = if (compactMode) 1 else 2
      binding.deviceName.ellipsize = TextUtils.TruncateAt.END
      binding.deviceStar.text = if (item.starred) "★" else "☆"
      binding.deviceStar.contentDescription = itemView.context.getString(
        if (item.starred) {
          ninja.unagi.R.string.unstar_device
        } else {
          ninja.unagi.R.string.star_device
        }
      )
      binding.deviceStar.setOnClickListener { onStarToggle(item, !item.starred) }
      binding.deviceMeta.text = item.metaLine
      binding.deviceMeta.isVisible = item.metaLine.isNotBlank()
      binding.deviceMeta.maxLines = if (compactMode) 2 else 4
      binding.deviceMeta.ellipsize = TextUtils.TruncateAt.END
      binding.deviceRssi.text = Formatters.formatRssi(item.lastRssi)
      binding.root.setOnClickListener { onClick(item) }
      binding.root.setOnLongClickListener {
        onLongClick(item)
        true
      }
    }

    private fun applyCardDensity(compactMode: Boolean) {
      val verticalMargin = dp(if (compactMode) 4 else 8)
      val contentPadding = dp(if (compactMode) 12 else 18)
      binding.root.radius = dp(if (compactMode) 16 else 20).toFloat()
      binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
        topMargin = verticalMargin
        bottomMargin = verticalMargin
      }
      binding.deviceContent.updatePadding(
        left = contentPadding,
        top = contentPadding,
        right = contentPadding,
        bottom = contentPadding
      )
      binding.deviceName.setTextSize(
        TypedValue.COMPLEX_UNIT_SP,
        if (compactMode) 14f else 16f
      )
      binding.deviceStar.setTextSize(
        TypedValue.COMPLEX_UNIT_SP,
        if (compactMode) 18f else 22f
      )
      binding.deviceMeta.setTextSize(
        TypedValue.COMPLEX_UNIT_SP,
        if (compactMode) 12f else 14f
      )
      binding.deviceRssi.setTextSize(
        TypedValue.COMPLEX_UNIT_SP,
        if (compactMode) 12f else 14f
      )
      binding.deviceMeta.updateLayoutParams<ViewGroup.MarginLayoutParams> {
        topMargin = dp(if (compactMode) 4 else 6)
      }
      binding.deviceRssi.updateLayoutParams<ViewGroup.MarginLayoutParams> {
        topMargin = dp(if (compactMode) 2 else 4)
      }
    }

    private fun dp(value: Int): Int {
      return (value * itemView.resources.displayMetrics.density).toInt()
    }
  }

  companion object {
    private const val RSSI_CHANGE_THRESHOLD_DBM = 4

    private val DiffCallback = object : DiffUtil.ItemCallback<DeviceListItem>() {
      override fun areItemsTheSame(oldItem: DeviceListItem, newItem: DeviceListItem): Boolean {
        return oldItem.deviceKey == newItem.deviceKey
      }

      override fun areContentsTheSame(oldItem: DeviceListItem, newItem: DeviceListItem): Boolean {
        return oldItem.deviceKey == newItem.deviceKey &&
          oldItem.displayName == newItem.displayName &&
          oldItem.displayTitle == newItem.displayTitle &&
          oldItem.metaLine == newItem.metaLine &&
          oldItem.searchText == newItem.searchText &&
          oldItem.sortTimestamp == newItem.sortTimestamp &&
          oldItem.sightingsCount == newItem.sightingsCount &&
          oldItem.starred == newItem.starred &&
          oldItem.lastAddress == newItem.lastAddress &&
          oldItem.vendorName == newItem.vendorName &&
          kotlin.math.abs(oldItem.lastRssi - newItem.lastRssi) < RSSI_CHANGE_THRESHOLD_DBM
      }
    }
  }
}
