package ninja.unagi.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
  @Query("SELECT * FROM devices")
  fun observeDevices(): Flow<List<DeviceEntity>>

  @Query("SELECT * FROM devices WHERE deviceKey = :deviceKey LIMIT 1")
  fun observeDevice(deviceKey: String): Flow<DeviceEntity?>

  @Query("SELECT * FROM devices ORDER BY lastSeen DESC")
  suspend fun getDevices(): List<DeviceEntity>

  @Query("SELECT * FROM devices WHERE deviceKey = :deviceKey LIMIT 1")
  suspend fun getDevice(deviceKey: String): DeviceEntity?

  @Query("SELECT * FROM devices WHERE deviceKey IN (:deviceKeys)")
  suspend fun getDevices(deviceKeys: List<String>): List<DeviceEntity>

  @Upsert
  suspend fun upsertDevice(device: DeviceEntity)

  @Upsert
  suspend fun upsertDevices(devices: List<DeviceEntity>)

  @Query("UPDATE devices SET starred = :starred WHERE deviceKey = :deviceKey")
  suspend fun setStarred(deviceKey: String, starred: Boolean)

  @Query("UPDATE devices SET userCustomName = :name WHERE deviceKey = :deviceKey")
  suspend fun setUserCustomName(deviceKey: String, name: String?)

  @Query("DELETE FROM devices WHERE lastSeen < :threshold")
  suspend fun deleteOlderThan(threshold: Long)
}
