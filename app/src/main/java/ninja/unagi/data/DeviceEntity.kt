package ninja.unagi.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "devices")
data class DeviceEntity(
  @PrimaryKey val deviceKey: String,
  val displayName: String?,
  val lastAddress: String?,
  val firstSeen: Long,
  val lastSeen: Long,
  val lastSightingAt: Long,
  val sightingsCount: Int,
  val observationCount: Int,
  val lastRssi: Int,
  val rssiMin: Int,
  val rssiMax: Int,
  val rssiAvg: Double,
  val lastMetadataJson: String?,
  val starred: Boolean,
  val userCustomName: String? = null,
  /** Comma-separated group IDs this device was imported from. Null = local-only. */
  val sharedFromGroupIds: String? = null
)
