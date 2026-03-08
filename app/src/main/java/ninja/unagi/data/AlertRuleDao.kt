package ninja.unagi.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AlertRuleDao {
  @Query("SELECT * FROM alert_rules ORDER BY createdAt DESC, id DESC")
  suspend fun getRules(): List<AlertRuleEntity>

  @Query("SELECT * FROM alert_rules ORDER BY createdAt DESC, id DESC")
  fun observeRules(): Flow<List<AlertRuleEntity>>

  @Query("SELECT * FROM alert_rules WHERE enabled = 1 ORDER BY createdAt DESC, id DESC")
  fun observeEnabledRules(): Flow<List<AlertRuleEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(rule: AlertRuleEntity): Long

  @Query("UPDATE alert_rules SET enabled = :enabled WHERE id = :id")
  suspend fun setEnabled(id: Long, enabled: Boolean)

  @Query("DELETE FROM alert_rules WHERE id = :id")
  suspend fun deleteById(id: Long)
}
