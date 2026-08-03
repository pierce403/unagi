package ninja.unagi.ui

import android.text.TextUtils
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ninja.unagi.R
import ninja.unagi.databinding.ItemDeviceBinding
import ninja.unagi.util.Formatters

class DeviceAdapter(
  private val onClick: (DeviceListItem) -> Unit,
  private val onLongClick: (DeviceListItem) -> Unit,
  private val onStarToggle: (DeviceListItem, Boolean) -> Unit,
  private val onNoteEdit: (DeviceListItem) -> Unit
) : ListAdapter<DeviceListItem, DeviceAdapter.DeviceViewHolder>(DiffCallback) {
  private var compactMode = false

  init {
    setHasStableIds(true)
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
    val binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    return DeviceViewHolder(binding, onClick, onLongClick, onStarToggle, onNoteEdit)
  }

  override fun getItemId(position: Int): Long {
    return stableId(getItem(position).deviceKey)
  }

  override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
    holder.bind(getItem(position), compactMode)
  }

  override fun onBindViewHolder(
    holder: DeviceViewHolder,
    position: Int,
    payloads: MutableList<Any>
  ) {
    val rssiPayload = payloads.lastOrNull { it is RssiPayload } as? RssiPayload
    if (rssiPayload != null && payloads.all { it is RssiPayload }) {
      holder.bindRssi(getItem(position), rssiPayload.rssi)
    } else {
      super.onBindViewHolder(holder, position, payloads)
    }
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
    private val onStarToggle: (DeviceListItem, Boolean) -> Unit,
    private val onNoteEdit: (DeviceListItem) -> Unit
  ) : RecyclerView.ViewHolder(binding.root) {
    private var boundItem: DeviceListItem? = null
    private var appliedCompactMode: Boolean? = null
    private var appliedSharedState: Boolean? = null

    init {
      binding.deviceNoteButton.setOnClickListener {
        boundItem?.let(onNoteEdit)
      }
      binding.deviceStar.setOnClickListener {
        boundItem?.let { item -> onStarToggle(item, !item.starred) }
      }
      binding.root.setOnClickListener {
        boundItem?.let(onClick)
      }
      binding.root.setOnLongClickListener {
        boundItem?.let(onLongClick)
        true
      }
    }

    fun bind(item: DeviceListItem, compactMode: Boolean) {
      boundItem = item
      if (appliedCompactMode != compactMode) {
        applyCardDensity(compactMode)
        appliedCompactMode = compactMode
      }
      val ctx = itemView.context
      if (appliedSharedState != item.isShared) {
        if (item.isShared) {
          binding.root.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.thingalert_surface_shared))
          binding.root.strokeColor = ContextCompat.getColor(ctx, R.color.thingalert_stroke_shared)
        } else {
          binding.root.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.thingalert_surface_variant))
          binding.root.strokeColor = ContextCompat.getColor(ctx, R.color.thingalert_stroke_soft)
        }
        appliedSharedState = item.isShared
      }
      binding.deviceName.text = item.displayTitle
      binding.deviceName.maxLines = if (compactMode) 1 else 2
      binding.deviceName.ellipsize = TextUtils.TruncateAt.END
      binding.deviceNoteButton.contentDescription = itemView.context.getString(
        if (item.deviceNote.isNullOrBlank()) {
          R.string.add_device_note
        } else {
          R.string.edit_device_note
        }
      )
      binding.deviceStar.text = if (item.starred) "★" else "☆"
      binding.deviceStar.contentDescription = itemView.context.getString(
        if (item.starred) {
          ninja.unagi.R.string.unstar_device
        } else {
          ninja.unagi.R.string.star_device
        }
      )
      binding.deviceMeta.text = item.metaLine
      binding.deviceMeta.isVisible = item.metaLine.isNotBlank()
      binding.deviceMeta.maxLines = if (compactMode) 2 else 4
      binding.deviceMeta.ellipsize = TextUtils.TruncateAt.END
      bindRssi(item, item.lastRssi)
    }

    fun bindRssi(item: DeviceListItem, rssi: Int) {
      boundItem = item
      binding.deviceRssi.text = Formatters.formatRssi(rssi)
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
      binding.deviceNoteButton.updateLayoutParams<ViewGroup.MarginLayoutParams> {
        width = dp(if (compactMode) 30 else 34)
        height = dp(if (compactMode) 30 else 34)
        marginStart = dp(if (compactMode) 6 else 8)
      }
      binding.deviceNoteButton.setPadding(
        dp(if (compactMode) 6 else 7),
        dp(if (compactMode) 6 else 7),
        dp(if (compactMode) 6 else 7),
        dp(if (compactMode) 6 else 7)
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
    private const val FNV_OFFSET_BASIS = -3750763034362895579L
    private const val FNV_PRIME = 1099511628211L

    private data class RssiPayload(val rssi: Int)

    private fun stableId(value: String): Long {
      var hash = FNV_OFFSET_BASIS
      value.forEach { character ->
        hash = hash xor character.code.toLong()
        hash *= FNV_PRIME
      }
      return hash
    }

    private fun sameContentExceptRssi(
      oldItem: DeviceListItem,
      newItem: DeviceListItem
    ): Boolean {
      return oldItem.deviceKey == newItem.deviceKey &&
        oldItem.displayName == newItem.displayName &&
        oldItem.displayTitle == newItem.displayTitle &&
        oldItem.deviceNote == newItem.deviceNote &&
        oldItem.metaLine == newItem.metaLine &&
        oldItem.sortTimestamp == newItem.sortTimestamp &&
        oldItem.sightingsCount == newItem.sightingsCount &&
        oldItem.starred == newItem.starred &&
        oldItem.lastAddress == newItem.lastAddress &&
        oldItem.vendorName == newItem.vendorName &&
        oldItem.sharedFromGroupIds == newItem.sharedFromGroupIds
    }

    private val DiffCallback = object : DiffUtil.ItemCallback<DeviceListItem>() {
      override fun areItemsTheSame(oldItem: DeviceListItem, newItem: DeviceListItem): Boolean {
        return oldItem.deviceKey == newItem.deviceKey
      }

      override fun areContentsTheSame(oldItem: DeviceListItem, newItem: DeviceListItem): Boolean {
        return sameContentExceptRssi(oldItem, newItem) &&
          oldItem.lastRssi == newItem.lastRssi
      }

      override fun getChangePayload(oldItem: DeviceListItem, newItem: DeviceListItem): Any? {
        return if (
          sameContentExceptRssi(oldItem, newItem) && oldItem.lastRssi != newItem.lastRssi
        ) {
          RssiPayload(newItem.lastRssi)
        } else {
          null
        }
      }
    }
  }
}
