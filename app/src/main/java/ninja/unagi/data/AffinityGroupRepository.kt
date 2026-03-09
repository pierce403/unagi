package ninja.unagi.data

import kotlinx.coroutines.flow.Flow

class AffinityGroupRepository(
  private val dao: AffinityGroupDao
) {
  fun observeGroups(): Flow<List<AffinityGroupEntity>> = dao.observeGroups()

  fun observeGroup(groupId: String): Flow<AffinityGroupEntity?> = dao.observeGroup(groupId)

  suspend fun getGroups(): List<AffinityGroupEntity> = dao.getGroups()

  suspend fun getGroup(groupId: String): AffinityGroupEntity? = dao.getGroup(groupId)

  suspend fun createGroup(group: AffinityGroupEntity) = dao.insert(group)

  suspend fun updateGroup(group: AffinityGroupEntity) = dao.update(group)

  suspend fun deleteGroup(groupId: String) {
    dao.deleteMembersByGroup(groupId)
    dao.deleteById(groupId)
  }

  // Members

  fun observeMembers(groupId: String): Flow<List<AffinityGroupMemberEntity>> =
    dao.observeMembers(groupId)

  suspend fun getMembers(groupId: String): List<AffinityGroupMemberEntity> =
    dao.getMembers(groupId)

  suspend fun addMember(member: AffinityGroupMemberEntity) = dao.insertMember(member)

  suspend fun revokeMember(groupId: String, memberId: String) =
    dao.revokeMember(groupId, memberId)

  // Import log

  suspend fun getImportLog(groupId: String): List<AffinityImportLogEntity> =
    dao.getImportLog(groupId)

  suspend fun hasImport(groupId: String, senderId: String, exportTimestamp: Long): Boolean =
    dao.hasImport(groupId, senderId, exportTimestamp) > 0

  suspend fun logImport(log: AffinityImportLogEntity) = dao.insertImportLog(log)
}
