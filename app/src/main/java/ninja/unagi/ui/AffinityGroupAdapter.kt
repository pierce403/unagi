package ninja.unagi.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ninja.unagi.R
import ninja.unagi.data.AffinityGroupEntity
import ninja.unagi.databinding.ItemAffinityGroupBinding
import java.text.DateFormat
import java.util.Date

/** Fix #18: uses string resources instead of concatenation. */
class AffinityGroupAdapter(
  private val onClick: (AffinityGroupEntity) -> Unit
) : ListAdapter<AffinityGroupEntity, AffinityGroupAdapter.ViewHolder>(DIFF) {

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
    val binding = ItemAffinityGroupBinding.inflate(
      LayoutInflater.from(parent.context), parent, false
    )
    return ViewHolder(binding)
  }

  override fun onBindViewHolder(holder: ViewHolder, position: Int) {
    holder.bind(getItem(position))
  }

  inner class ViewHolder(
    private val binding: ItemAffinityGroupBinding
  ) : RecyclerView.ViewHolder(binding.root) {
    fun bind(group: AffinityGroupEntity) {
      val ctx = binding.root.context
      binding.groupName.text = group.groupName
      val created = DateFormat.getDateInstance().format(Date(group.createdAt))
      binding.groupInfo.text = ctx.getString(R.string.group_list_info, created, group.keyEpoch)
      binding.root.setOnClickListener { onClick(group) }
    }
  }

  companion object {
    private val DIFF = object : DiffUtil.ItemCallback<AffinityGroupEntity>() {
      override fun areItemsTheSame(a: AffinityGroupEntity, b: AffinityGroupEntity) =
        a.groupId == b.groupId
      override fun areContentsTheSame(a: AffinityGroupEntity, b: AffinityGroupEntity) =
        a == b
    }
  }
}
