package ninja.unagi.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AffinityGroupDao {
  @Query("SELECT * FROM affinity_groups ORDER BY createdAt DESC")
  fun observeGroups(): Flow<List<AffinityGroupEntity>>

  @Query("SELECT * FROM affinity_groups WHERE groupId = :groupId")
  fun observeGroup(groupId: String): Flow<AffinityGroupEntity?>

  @Query("SELECT * FROM affinity_groups ORDER BY createdAt DESC")
  suspend fun getGroups(): List<AffinityGroupEntity>

  @Query("SELECT * FROM affinity_groups WHERE groupId = :groupId")
  suspend fun getGroup(groupId: String): AffinityGroupEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(group: AffinityGroupEntity)

  @Update
  suspend fun update(group: AffinityGroupEntity)

  @Query("DELETE FROM affinity_groups WHERE groupId = :groupId")
  suspend fun deleteById(groupId: String)

  // Members

  @Query("SELECT * FROM affinity_group_members WHERE groupId = :groupId ORDER BY joinedAt ASC")
  fun observeMembers(groupId: String): Flow<List<AffinityGroupMemberEntity>>

  @Query("SELECT * FROM affinity_group_members WHERE groupId = :groupId ORDER BY joinedAt ASC")
  suspend fun getMembers(groupId: String): List<AffinityGroupMemberEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertMember(member: AffinityGroupMemberEntity)

  @Query("UPDATE affinity_group_members SET revoked = 1 WHERE groupId = :groupId AND memberId = :memberId")
  suspend fun revokeMember(groupId: String, memberId: String)

  @Query("DELETE FROM affinity_group_members WHERE groupId = :groupId")
  suspend fun deleteMembersByGroup(groupId: String)

  // Import log

  @Query("SELECT * FROM affinity_import_log WHERE groupId = :groupId ORDER BY importedAt DESC")
  suspend fun getImportLog(groupId: String): List<AffinityImportLogEntity>

  @Query("SELECT COUNT(*) FROM affinity_import_log WHERE groupId = :groupId AND senderId = :senderId AND exportTimestamp = :exportTimestamp")
  suspend fun hasImport(groupId: String, senderId: String, exportTimestamp: Long): Int

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertImportLog(log: AffinityImportLogEntity)
}
