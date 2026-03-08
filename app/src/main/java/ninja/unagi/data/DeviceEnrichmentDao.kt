package ninja.unagi.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceEnrichmentDao {
  @Query("SELECT * FROM device_enrichments ORDER BY lastQueryTimestamp DESC")
  fun observeEnrichments(): Flow<List<DeviceEnrichmentEntity>>

  @Query("SELECT * FROM device_enrichments WHERE deviceKey = :deviceKey LIMIT 1")
  fun observeEnrichment(deviceKey: String): Flow<DeviceEnrichmentEntity?>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertEnrichment(enrichment: DeviceEnrichmentEntity)
}
