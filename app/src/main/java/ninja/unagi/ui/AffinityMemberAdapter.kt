package ninja.unagi.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ninja.unagi.R
import ninja.unagi.data.AffinityGroupMemberEntity
import ninja.unagi.databinding.ItemAffinityMemberBinding

class AffinityMemberAdapter(
  private val myMemberId: String,
  private val onRevoke: ((AffinityGroupMemberEntity) -> Unit)? = null
) : ListAdapter<AffinityGroupMemberEntity, AffinityMemberAdapter.ViewHolder>(DIFF) {

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
    val binding = ItemAffinityMemberBinding.inflate(
      LayoutInflater.from(parent.context), parent, false
    )
    return ViewHolder(binding)
  }

  override fun onBindViewHolder(holder: ViewHolder, position: Int) {
    holder.bind(getItem(position))
  }

  inner class ViewHolder(
    private val binding: ItemAffinityMemberBinding
  ) : RecyclerView.ViewHolder(binding.root) {
    fun bind(member: AffinityGroupMemberEntity) {
      val ctx = binding.root.context
      val suffix = when {
        member.memberId == myMemberId -> ctx.getString(R.string.member_you)
        member.revoked -> ctx.getString(R.string.member_revoked)
        else -> ""
      }
      binding.memberName.text = if (suffix.isNotEmpty()) {
        ctx.getString(R.string.member_name_with_suffix, member.displayName, suffix)
      } else {
        member.displayName
      }
      binding.memberStatus.text = ctx.getString(R.string.member_epoch, member.lastSeenEpoch)

      // Long-press to revoke (only for non-self, non-revoked members)
      if (member.memberId != myMemberId && !member.revoked && onRevoke != null) {
        binding.root.setOnLongClickListener {
          onRevoke.invoke(member)
          true
        }
      } else {
        binding.root.setOnLongClickListener(null)
        binding.root.isLongClickable = false
      }
    }
  }

  companion object {
    private val DIFF = object : DiffUtil.ItemCallback<AffinityGroupMemberEntity>() {
      override fun areItemsTheSame(a: AffinityGroupMemberEntity, b: AffinityGroupMemberEntity) =
        a.groupId == b.groupId && a.memberId == b.memberId
      override fun areContentsTheSame(a: AffinityGroupMemberEntity, b: AffinityGroupMemberEntity) =
        a == b
    }
  }
}
