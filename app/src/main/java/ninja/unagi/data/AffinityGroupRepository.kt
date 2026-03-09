package ninja.unagi.data

import kotlinx.coroutines.flow.Flow
import ninja.unagi.group.EcdhKeyManager
import ninja.unagi.group.GroupKeyManager

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
    // P9: clean up ECDH Keystore entries before deleting members
    val members = dao.getMembers(groupId)
    for (member in members) {
      EcdhKeyManager.deleteKeypair(groupId, member.memberId)
    }
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

  /**
   * Revoke a member and rotate the group key so they can't decrypt new bundles.
   * Auto-enables requireEcdh on first revocation (P8 mitigation).
   * Returns the updated group entity.
   */
  suspend fun revokeMemberAndRotateKey(groupId: String, memberId: String): AffinityGroupEntity? {
    dao.revokeMember(groupId, memberId)
    EcdhKeyManager.deleteKeypair(groupId, memberId)

    val group = dao.getGroup(groupId) ?: return null
    val (_, newWrappedKey) = GroupKeyManager.rotateGroupKey()
    val updated = group.copy(
      groupKeyWrapped = newWrappedKey,
      keyEpoch = group.keyEpoch + 1,
      requireEcdh = true  // P8: auto-enable requireEcdh on first revocation
    )
    dao.update(updated)
    return updated
  }

  // Import log

  suspend fun getImportLog(groupId: String): List<AffinityImportLogEntity> =
    dao.getImportLog(groupId)

  suspend fun hasImport(groupId: String, senderId: String, exportTimestamp: Long): Boolean =
    dao.hasImport(groupId, senderId, exportTimestamp) > 0

  suspend fun logImport(log: AffinityImportLogEntity) = dao.insertImportLog(log)
}
