package ninja.unagi.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ninja.unagi.alerts.displayMeta
import ninja.unagi.alerts.displayTitle
import ninja.unagi.data.AlertRuleEntity
import ninja.unagi.databinding.ItemAlertRuleBinding

class AlertRuleAdapter(
  private val onEnabledChanged: (AlertRuleEntity, Boolean) -> Unit,
  private val onDelete: (AlertRuleEntity) -> Unit
) : ListAdapter<AlertRuleEntity, AlertRuleAdapter.AlertRuleViewHolder>(DiffCallback) {

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlertRuleViewHolder {
    val binding = ItemAlertRuleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    return AlertRuleViewHolder(binding, onEnabledChanged, onDelete)
  }

  override fun onBindViewHolder(holder: AlertRuleViewHolder, position: Int) {
    holder.bind(getItem(position))
  }

  class AlertRuleViewHolder(
    private val binding: ItemAlertRuleBinding,
    private val onEnabledChanged: (AlertRuleEntity, Boolean) -> Unit,
    private val onDelete: (AlertRuleEntity) -> Unit
  ) : RecyclerView.ViewHolder(binding.root) {
    fun bind(item: AlertRuleEntity) {
      binding.ruleEmoji.text = item.emoji
      binding.ruleTitle.text = item.displayTitle()
      binding.ruleMeta.text = item.displayMeta()
      binding.ruleEnabled.setOnCheckedChangeListener(null)
      binding.ruleEnabled.isChecked = item.enabled
      binding.ruleEnabled.setOnCheckedChangeListener { _, isChecked ->
        onEnabledChanged(item, isChecked)
      }
      binding.deleteRuleButton.setOnClickListener { onDelete(item) }
    }
  }

  companion object {
    private val DiffCallback = object : DiffUtil.ItemCallback<AlertRuleEntity>() {
      override fun areItemsTheSame(oldItem: AlertRuleEntity, newItem: AlertRuleEntity): Boolean {
        return oldItem.id == newItem.id
      }

      override fun areContentsTheSame(oldItem: AlertRuleEntity, newItem: AlertRuleEntity): Boolean {
        return oldItem == newItem
      }
    }
  }
}
