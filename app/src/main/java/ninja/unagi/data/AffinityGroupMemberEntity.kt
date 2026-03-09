package ninja.unagi.data

import androidx.room.Entity

@Entity(
  tableName = "affinity_group_members",
  primaryKeys = ["groupId", "memberId"]
)
data class AffinityGroupMemberEntity(
  val groupId: String,
  val memberId: String,
  val displayName: String,
  val joinedAt: Long,
  val lastSeenEpoch: Int,
  val publicKeyBase64: String?,
  val revoked: Boolean
)
