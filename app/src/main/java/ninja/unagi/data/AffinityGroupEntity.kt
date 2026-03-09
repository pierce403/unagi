package ninja.unagi.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "affinity_groups")
data class AffinityGroupEntity(
  @PrimaryKey val groupId: String,
  val groupName: String,
  val createdAt: Long,
  val myMemberId: String,
  val myDisplayName: String,
  val groupKeyWrapped: String,
  val keyEpoch: Int,
  val sharingConfigJson: String
)
