package ninja.unagi.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SightingDao {
  @Query("SELECT * FROM sightings WHERE deviceKey = :deviceKey ORDER BY timestamp DESC")
  fun observeSightings(deviceKey: String): Flow<List<SightingEntity>>

  @Query("SELECT * FROM sightings ORDER BY timestamp DESC")
  suspend fun getSightings(): List<SightingEntity>

  @Query("SELECT deviceKey || '|' || timestamp FROM sightings WHERE deviceKey IN (:deviceKeys)")
  suspend fun getExistingSightingKeys(deviceKeys: List<String>): List<String>

  @Insert
  suspend fun insertSighting(sighting: SightingEntity)

  @Query("DELETE FROM sightings WHERE timestamp < :threshold")
  suspend fun pruneOlderThan(threshold: Long)
}
