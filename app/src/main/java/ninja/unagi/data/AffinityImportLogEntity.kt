package ninja.unagi.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "affinity_import_log",
  indices = [Index(value = ["groupId", "senderId", "exportTimestamp"], unique = true)]
)
data class AffinityImportLogEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val groupId: String,
  val senderId: String,
  val exportTimestamp: Long,
  val importedAt: Long,
  val itemCounts: String
)
