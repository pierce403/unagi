package ninja.unagi.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
  @Query("SELECT * FROM devices ORDER BY lastSeen DESC")
  fun observeDevices(): Flow<List<DeviceEntity>>

  @Query("SELECT * FROM devices WHERE deviceKey = :deviceKey LIMIT 1")
  fun observeDevice(deviceKey: String): Flow<DeviceEntity?>

  @Query("SELECT * FROM devices ORDER BY lastSeen DESC")
  suspend fun getDevices(): List<DeviceEntity>

  @Query("SELECT * FROM devices WHERE deviceKey = :deviceKey LIMIT 1")
  suspend fun getDevice(deviceKey: String): DeviceEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertDevice(device: DeviceEntity)

  @Query("UPDATE devices SET starred = :starred WHERE deviceKey = :deviceKey")
  suspend fun setStarred(deviceKey: String, starred: Boolean)

  @Query("UPDATE devices SET userCustomName = :name WHERE deviceKey = :deviceKey")
  suspend fun setUserCustomName(deviceKey: String, name: String?)

  @Query("DELETE FROM devices WHERE lastSeen < :threshold")
  suspend fun deleteOlderThan(threshold: Long)
}
