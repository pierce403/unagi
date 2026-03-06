package com.thingalert.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SightingDao {
  @Query("SELECT * FROM sightings WHERE deviceKey = :deviceKey ORDER BY timestamp DESC")
  fun observeSightings(deviceKey: String): Flow<List<SightingEntity>>

  @Insert
  suspend fun insertSighting(sighting: SightingEntity)

  @Query("DELETE FROM sightings WHERE timestamp < :threshold")
  suspend fun pruneOlderThan(threshold: Long)
}
