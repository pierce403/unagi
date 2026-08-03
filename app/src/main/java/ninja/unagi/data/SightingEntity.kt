package ninja.unagi.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "sightings",
  indices = [
    Index(value = ["timestamp"]),
    Index(value = ["deviceKey", "timestamp"])
  ]
)
data class SightingEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val deviceKey: String,
  val timestamp: Long,
  val rssi: Int,
  val name: String?,
  val address: String?,
  val metadataJson: String?
)
