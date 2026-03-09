package ninja.unagi.group

import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import ninja.unagi.data.AffinityGroupDao
import ninja.unagi.data.AffinityGroupEntity
import ninja.unagi.data.AffinityGroupMemberEntity
import ninja.unagi.data.AffinityGroupRepository
import ninja.unagi.data.AppDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * On-device integration tests for affinity group key management, creation,
 * revocation, and bundle export/import. Requires Android Keystore (emulator or device).
 */
@RunWith(AndroidJUnit4::class)
class AffinityGroupIntegrationTest {

  private lateinit var db: AppDatabase
  private lateinit var dao: AffinityGroupDao
  private lateinit var repository: AffinityGroupRepository

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    // Use in-memory Room DB (no SQLCipher) for test isolation
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    dao = db.affinityGroupDao()
    repository = AffinityGroupRepository(dao)
  }

  @After
  fun tearDown() {
    db.close()
  }

  // ── Group Key Manager: wrap / unwrap round-trip ──

  @Test
  fun groupKey_wrapAndUnwrap_roundTrip() {
    val rawKey = GroupKeyManager.generateGroupKey()
    val wrapped = GroupKeyManager.wrapKey(rawKey)

    assertNotNull(wrapped)
    assertTrue("Wrapped key should be Base64", wrapped.isNotEmpty())

    val unwrapped = GroupKeyManager.unwrapKey(wrapped)
    assertArrayEquals(
      "Unwrapped key should match original",
      rawKey.encoded,
      unwrapped.encoded
    )
  }

  @Test
  fun groupKey_generateMultipleKeys_areDistinct() {
    val key1 = GroupKeyManager.generateGroupKey()
    val key2 = GroupKeyManager.generateGroupKey()
    assertFalse(
      "Two generated keys should be different",
      key1.encoded.contentEquals(key2.encoded)
    )
  }

  @Test
  fun groupKey_exportAndImport_roundTrip() {
    val rawKey = GroupKeyManager.generateGroupKey()
    val exported = GroupKeyManager.exportKeyForSharing(rawKey)
    val imported = GroupKeyManager.importKeyFromSharing(exported)
    assertArrayEquals(rawKey.encoded, imported.encoded)
  }

  @Test
  fun groupKey_checksum_verifies() {
    val rawKey = GroupKeyManager.generateGroupKey()
    val groupId = UUID.randomUUID().toString()
    val checksum = GroupKeyManager.computeKeyChecksum(rawKey, groupId)

    assertTrue("Checksum should be 16 hex chars", checksum.length == 16)
    assertTrue(GroupKeyManager.verifyKeyChecksum(rawKey, groupId, checksum))
  }

  @Test
  fun groupKey_checksum_rejectsWrongKey() {
    val key1 = GroupKeyManager.generateGroupKey()
    val key2 = GroupKeyManager.generateGroupKey()
    val groupId = UUID.randomUUID().toString()
    val checksum = GroupKeyManager.computeKeyChecksum(key1, groupId)

    assertFalse(
      "Checksum should not verify with wrong key",
      GroupKeyManager.verifyKeyChecksum(key2, groupId, checksum)
    )
  }

  @Test
  fun groupKey_checksum_rejectsWrongGroupId() {
    val rawKey = GroupKeyManager.generateGroupKey()
    val groupId1 = UUID.randomUUID().toString()
    val groupId2 = UUID.randomUUID().toString()
    val checksum = GroupKeyManager.computeKeyChecksum(rawKey, groupId1)

    assertFalse(
      "Checksum should not verify with wrong groupId",
      GroupKeyManager.verifyKeyChecksum(rawKey, groupId2, checksum)
    )
  }

  @Test
  fun groupKey_rotateGroupKey_producesNewKey() {
    val (newKey, wrapped) = GroupKeyManager.rotateGroupKey()
    assertNotNull(newKey)
    assertNotNull(wrapped)
    assertTrue(wrapped.isNotEmpty())

    val unwrapped = GroupKeyManager.unwrapKey(wrapped)
    assertArrayEquals(newKey.encoded, unwrapped.encoded)
  }

  // ── ECDH Key Manager ──

  @Test
  fun ecdh_generateKeypair_createsKeystoreEntry() {
    val groupId = UUID.randomUUID().toString()
    val memberId = UUID.randomUUID().toString()

    try {
      val publicKeyBase64 = EcdhKeyManager.generateKeypair(groupId, memberId)
      assertNotNull(publicKeyBase64)
      assertTrue("Public key should be Base64-encoded", publicKeyBase64.isNotEmpty())

      // Decode public key should be valid
      val decoded = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
      assertTrue("Public key should be >30 bytes (X.509 SubjectPublicKeyInfo)", decoded.size > 30)

      assertTrue(EcdhKeyManager.hasKeypair(groupId, memberId))
      assertEquals(publicKeyBase64, EcdhKeyManager.getPublicKey(groupId, memberId))
    } finally {
      EcdhKeyManager.deleteKeypair(groupId, memberId)
    }
  }

  @Test
  fun ecdh_generateKeypair_idempotent() {
    val groupId = UUID.randomUUID().toString()
    val memberId = UUID.randomUUID().toString()

    try {
      val pk1 = EcdhKeyManager.generateKeypair(groupId, memberId)
      val pk2 = EcdhKeyManager.generateKeypair(groupId, memberId)
      assertEquals("Idempotent generation should return same key", pk1, pk2)
    } finally {
      EcdhKeyManager.deleteKeypair(groupId, memberId)
    }
  }

  @Test
  fun ecdh_deleteKeypair_removesEntry() {
    val groupId = UUID.randomUUID().toString()
    val memberId = UUID.randomUUID().toString()

    EcdhKeyManager.generateKeypair(groupId, memberId)
    assertTrue(EcdhKeyManager.hasKeypair(groupId, memberId))

    EcdhKeyManager.deleteKeypair(groupId, memberId)
    assertFalse(EcdhKeyManager.hasKeypair(groupId, memberId))
    assertNull(EcdhKeyManager.getPublicKey(groupId, memberId))
  }

  @Test
  fun ecdh_deriveSharedSecret_symmetry() {
    // Alice and Bob each generate keypairs
    val groupId = UUID.randomUUID().toString()
    val aliceId = UUID.randomUUID().toString()
    val bobId = UUID.randomUUID().toString()

    try {
      val alicePk = EcdhKeyManager.generateKeypair(groupId, aliceId)
      val bobPk = EcdhKeyManager.generateKeypair(groupId, bobId)

      val info = "test-info".toByteArray(Charsets.UTF_8)

      // Alice derives shared secret using Bob's public key
      val secretAlice = EcdhKeyManager.deriveSharedSecret(groupId, aliceId, bobPk, info)
      // Bob derives shared secret using Alice's public key
      val secretBob = EcdhKeyManager.deriveSharedSecret(groupId, bobId, alicePk, info)

      assertEquals("Shared secrets should be 32 bytes", 32, secretAlice.size)
      assertArrayEquals("ECDH shared secret should be symmetric", secretAlice, secretBob)
    } finally {
      EcdhKeyManager.deleteKeypair(groupId, aliceId)
      EcdhKeyManager.deleteKeypair(groupId, bobId)
    }
  }

  @Test
  fun ecdh_deriveSharedSecret_differentInfoProducesDifferentSecrets() {
    val groupId = UUID.randomUUID().toString()
    val aliceId = UUID.randomUUID().toString()
    val bobId = UUID.randomUUID().toString()

    try {
      val alicePk = EcdhKeyManager.generateKeypair(groupId, aliceId)
      val bobPk = EcdhKeyManager.generateKeypair(groupId, bobId)

      val secret1 = EcdhKeyManager.deriveSharedSecret(
        groupId, aliceId, bobPk, "info-1".toByteArray()
      )
      val secret2 = EcdhKeyManager.deriveSharedSecret(
        groupId, aliceId, bobPk, "info-2".toByteArray()
      )

      assertFalse("Different HKDF info should produce different secrets",
        secret1.contentEquals(secret2))
    } finally {
      EcdhKeyManager.deleteKeypair(groupId, aliceId)
      EcdhKeyManager.deleteKeypair(groupId, bobId)
    }
  }

  // ── Database: Group CRUD ──

  @Test
  fun database_createAndRetrieveGroup() = runBlocking {
    val groupId = UUID.randomUUID().toString()
    val rawKey = GroupKeyManager.generateGroupKey()
    val wrapped = GroupKeyManager.wrapKey(rawKey)

    val group = AffinityGroupEntity(
      groupId = groupId,
      groupName = "Test Group",
      createdAt = System.currentTimeMillis(),
      myMemberId = UUID.randomUUID().toString(),
      myDisplayName = "Alice",
      groupKeyWrapped = wrapped,
      keyEpoch = 1,
      sharingConfigJson = GroupSharingConfig().toJson()
    )

    repository.createGroup(group)
    val retrieved = repository.getGroup(groupId)

    assertNotNull(retrieved)
    assertEquals("Test Group", retrieved!!.groupName)
    assertEquals("Alice", retrieved.myDisplayName)
    assertEquals(1, retrieved.keyEpoch)
    assertFalse(retrieved.requireEcdh)

    // Verify the wrapped key can be unwrapped back
    val unwrapped = GroupKeyManager.unwrapKey(retrieved.groupKeyWrapped)
    assertArrayEquals(rawKey.encoded, unwrapped.encoded)
  }

  @Test
  fun database_addMembersAndRetrieve() = runBlocking {
    val groupId = UUID.randomUUID().toString()
    val aliceId = UUID.randomUUID().toString()
    val bobId = UUID.randomUUID().toString()

    createTestGroup(groupId, aliceId, "Alice")

    val bobPk = try {
      EcdhKeyManager.generateKeypair(groupId, bobId)
    } catch (_: Exception) { null }

    repository.addMember(AffinityGroupMemberEntity(
      groupId = groupId,
      memberId = aliceId,
      displayName = "Alice",
      joinedAt = System.currentTimeMillis(),
      lastSeenEpoch = 1,
      publicKeyBase64 = null,
      revoked = false
    ))
    repository.addMember(AffinityGroupMemberEntity(
      groupId = groupId,
      memberId = bobId,
      displayName = "Bob",
      joinedAt = System.currentTimeMillis() + 1,
      lastSeenEpoch = 1,
      publicKeyBase64 = bobPk,
      revoked = false
    ))

    val members = repository.getMembers(groupId)
    assertEquals(2, members.size)
    assertEquals("Alice", members[0].displayName)
    assertEquals("Bob", members[1].displayName)
    assertFalse(members[1].revoked)

    EcdhKeyManager.deleteKeypair(groupId, bobId)
  }

  // ── Revocation and key rotation ──

  @Test
  fun revocation_revokeMemberAndRotateKey() = runBlocking {
    val groupId = UUID.randomUUID().toString()
    val aliceId = UUID.randomUUID().toString()
    val bobId = UUID.randomUUID().toString()

    val rawKey = GroupKeyManager.generateGroupKey()
    val wrapped = GroupKeyManager.wrapKey(rawKey)

    val group = AffinityGroupEntity(
      groupId = groupId,
      groupName = "Revocation Test",
      createdAt = System.currentTimeMillis(),
      myMemberId = aliceId,
      myDisplayName = "Alice",
      groupKeyWrapped = wrapped,
      keyEpoch = 1,
      sharingConfigJson = GroupSharingConfig().toJson()
    )
    repository.createGroup(group)

    // Add Bob as a member with an ECDH keypair
    try { EcdhKeyManager.generateKeypair(groupId, bobId) } catch (_: Exception) {}

    repository.addMember(AffinityGroupMemberEntity(
      groupId = groupId, memberId = aliceId, displayName = "Alice",
      joinedAt = System.currentTimeMillis(), lastSeenEpoch = 1,
      publicKeyBase64 = null, revoked = false
    ))
    repository.addMember(AffinityGroupMemberEntity(
      groupId = groupId, memberId = bobId, displayName = "Bob",
      joinedAt = System.currentTimeMillis(), lastSeenEpoch = 1,
      publicKeyBase64 = EcdhKeyManager.getPublicKey(groupId, bobId),
      revoked = false
    ))

    // Revoke Bob
    val updated = repository.revokeMemberAndRotateKey(groupId, bobId)

    assertNotNull(updated)
    assertEquals(2, updated!!.keyEpoch)
    assertTrue("requireEcdh should be enabled after revocation (P8)", updated.requireEcdh)

    // Bob's ECDH keypair should be deleted
    assertFalse(EcdhKeyManager.hasKeypair(groupId, bobId))

    // The new wrapped key should differ from the original
    assertNotEquals(wrapped, updated.groupKeyWrapped)

    // The new key should still be unwrappable
    val newUnwrapped = GroupKeyManager.unwrapKey(updated.groupKeyWrapped)
    assertNotNull(newUnwrapped)
    assertEquals(32, newUnwrapped.encoded.size)

    // Old and new keys should differ
    assertFalse(rawKey.encoded.contentEquals(newUnwrapped.encoded))

    // Bob should be marked revoked in DB
    val members = repository.getMembers(groupId)
    val bob = members.find { it.memberId == bobId }
    assertNotNull(bob)
    assertTrue("Bob should be revoked", bob!!.revoked)
  }

  @Test
  fun revocation_groupKeyEpochIncrements() = runBlocking {
    val groupId = UUID.randomUUID().toString()
    val aliceId = UUID.randomUUID().toString()
    val bobId = UUID.randomUUID().toString()
    val carolId = UUID.randomUUID().toString()

    createTestGroup(groupId, aliceId, "Alice")

    repository.addMember(makeMember(groupId, aliceId, "Alice"))
    repository.addMember(makeMember(groupId, bobId, "Bob"))
    repository.addMember(makeMember(groupId, carolId, "Carol"))

    // Revoke Bob → epoch 2
    repository.revokeMemberAndRotateKey(groupId, bobId)
    var group = repository.getGroup(groupId)!!
    assertEquals(2, group.keyEpoch)

    // Revoke Carol → epoch 3
    repository.revokeMemberAndRotateKey(groupId, carolId)
    group = repository.getGroup(groupId)!!
    assertEquals(3, group.keyEpoch)
  }

  // ── Group deletion ──

  @Test
  fun deletion_removesGroupAndMembers() = runBlocking {
    val groupId = UUID.randomUUID().toString()
    val aliceId = UUID.randomUUID().toString()
    val bobId = UUID.randomUUID().toString()

    createTestGroup(groupId, aliceId, "Alice")
    repository.addMember(makeMember(groupId, aliceId, "Alice"))
    repository.addMember(makeMember(groupId, bobId, "Bob"))

    assertEquals(2, repository.getMembers(groupId).size)

    repository.deleteGroup(groupId)

    assertNull(repository.getGroup(groupId))
    assertEquals(0, repository.getMembers(groupId).size)
  }

  // ── Bundle export/import round-trip (symmetric path) ──

  @Test
  fun bundle_exportAndImport_symmetricRoundTrip() = runBlocking {
    val groupId = UUID.randomUUID().toString()
    val aliceId = UUID.randomUUID().toString()
    val rawKey = GroupKeyManager.generateGroupKey()
    val wrapped = GroupKeyManager.wrapKey(rawKey)

    val group = AffinityGroupEntity(
      groupId = groupId,
      groupName = "Bundle Test",
      createdAt = System.currentTimeMillis(),
      myMemberId = aliceId,
      myDisplayName = "Alice",
      groupKeyWrapped = wrapped,
      keyEpoch = 1,
      sharingConfigJson = GroupSharingConfig().toJson()
    )
    repository.createGroup(group)

    val exporter = BundleExporter(
      db.deviceDao(), db.sightingDao(), db.alertRuleDao(), db.deviceEnrichmentDao()
    )
    val config = GroupSharingConfig()
    val bundleBytes = exporter.export(group, config)

    assertNotNull(bundleBytes)
    assertTrue("Bundle should not be empty", bundleBytes.isNotEmpty())

    // Verify it's a valid ZIP by reading it
    val zipStream = java.util.zip.ZipInputStream(bundleBytes.inputStream())
    val entries = mutableListOf<String>()
    var entry = zipStream.nextEntry
    while (entry != null) {
      entries.add(entry.name)
      zipStream.closeEntry()
      entry = zipStream.nextEntry
    }
    zipStream.close()

    assertTrue("Bundle should contain manifest.json", "manifest.json" in entries)
    assertTrue("Bundle should contain payload.enc", "payload.enc" in entries)

    // Import the bundle
    val merger = DataMerger(db, db.deviceDao(), db.sightingDao(), db.alertRuleDao(), db.deviceEnrichmentDao())
    val importer = BundleImporter(repository, merger)
    val result = importer.importBundle(bundleBytes.inputStream())

    assertTrue("Import should succeed: ${(result as? BundleImporter.ImportResult.Error)?.message}",
      result is BundleImporter.ImportResult.Success)

    val success = result as BundleImporter.ImportResult.Success
    assertEquals(groupId, success.manifest.groupId)
    assertEquals(1, success.manifest.keyEpoch)
  }

  @Test
  fun bundle_rejectsDuplicateImport() = runBlocking {
    val groupId = UUID.randomUUID().toString()
    val aliceId = UUID.randomUUID().toString()
    val rawKey = GroupKeyManager.generateGroupKey()
    val wrapped = GroupKeyManager.wrapKey(rawKey)

    val group = AffinityGroupEntity(
      groupId = groupId,
      groupName = "Dedup Test",
      createdAt = System.currentTimeMillis(),
      myMemberId = aliceId,
      myDisplayName = "Alice",
      groupKeyWrapped = wrapped,
      keyEpoch = 1,
      sharingConfigJson = GroupSharingConfig().toJson()
    )
    repository.createGroup(group)

    val exporter = BundleExporter(
      db.deviceDao(), db.sightingDao(), db.alertRuleDao(), db.deviceEnrichmentDao()
    )
    val bundleBytes = exporter.export(group, GroupSharingConfig())

    val merger = DataMerger(db, db.deviceDao(), db.sightingDao(), db.alertRuleDao(), db.deviceEnrichmentDao())
    val importer = BundleImporter(repository, merger)

    // First import succeeds
    val result1 = importer.importBundle(bundleBytes.inputStream())
    assertTrue(result1 is BundleImporter.ImportResult.Success)

    // Second import of same bundle should be rejected
    val result2 = importer.importBundle(bundleBytes.inputStream())
    assertTrue("Duplicate import should be rejected", result2 is BundleImporter.ImportResult.Error)
    assertTrue(
      (result2 as BundleImporter.ImportResult.Error).message.contains("already been imported")
    )
  }

  @Test
  fun bundle_rejectsStaleEpoch() = runBlocking {
    val groupId = UUID.randomUUID().toString()
    val aliceId = UUID.randomUUID().toString()
    val bobId = UUID.randomUUID().toString()
    val rawKey = GroupKeyManager.generateGroupKey()
    val wrapped = GroupKeyManager.wrapKey(rawKey)

    val group = AffinityGroupEntity(
      groupId = groupId,
      groupName = "Epoch Test",
      createdAt = System.currentTimeMillis(),
      myMemberId = aliceId,
      myDisplayName = "Alice",
      groupKeyWrapped = wrapped,
      keyEpoch = 1,
      sharingConfigJson = GroupSharingConfig().toJson()
    )
    repository.createGroup(group)
    repository.addMember(makeMember(groupId, aliceId, "Alice"))
    repository.addMember(makeMember(groupId, bobId, "Bob"))

    // Export bundle at epoch 1
    val exporter = BundleExporter(
      db.deviceDao(), db.sightingDao(), db.alertRuleDao(), db.deviceEnrichmentDao()
    )
    val bundleBytes = exporter.export(group, GroupSharingConfig())

    // Revoke Bob → epoch 2
    repository.revokeMemberAndRotateKey(groupId, bobId)

    // Try importing the epoch-1 bundle with epoch-2 group
    val merger = DataMerger(db, db.deviceDao(), db.sightingDao(), db.alertRuleDao(), db.deviceEnrichmentDao())
    val importer = BundleImporter(repository, merger)
    val result = importer.importBundle(bundleBytes.inputStream())

    assertTrue("Stale epoch bundle should be rejected", result is BundleImporter.ImportResult.Error)
    assertTrue(
      (result as BundleImporter.ImportResult.Error).message.contains("outdated key epoch")
    )
  }

  // ── HKDF ──

  @Test
  fun hkdf_derivesConsistentOutput() {
    val ikm = ByteArray(32) { it.toByte() }
    val info = "test-info".toByteArray()
    val key1 = Hkdf.deriveKey(ikm, salt = null, info = info, length = 32)
    val key2 = Hkdf.deriveKey(ikm, salt = null, info = info, length = 32)
    assertArrayEquals("HKDF should be deterministic", key1, key2)
  }

  @Test
  fun hkdf_differentInfoDifferentOutput() {
    val ikm = ByteArray(32) { it.toByte() }
    val key1 = Hkdf.deriveKey(ikm, salt = null, info = "info-a".toByteArray(), length = 32)
    val key2 = Hkdf.deriveKey(ikm, salt = null, info = "info-b".toByteArray(), length = 32)
    assertFalse("Different info should produce different keys", key1.contentEquals(key2))
  }

  // ── GroupSharingConfig ──

  @Test
  fun sharingConfig_serializeDeserializeRoundTrip() {
    val config = GroupSharingConfig(
      devices = true,
      sightings = false,
      alertRules = true,
      enrichments = false,
      starredDevices = true,
      tpmsReadings = false,
      exportWindowDays = 14
    )
    val json = config.toJson()
    val restored = GroupSharingConfig.fromJson(json)

    assertEquals(config.devices, restored.devices)
    assertEquals(config.sightings, restored.sightings)
    assertEquals(config.alertRules, restored.alertRules)
    assertEquals(config.enrichments, restored.enrichments)
    assertEquals(config.starredDevices, restored.starredDevices)
    assertEquals(config.tpmsReadings, restored.tpmsReadings)
    assertEquals(config.exportWindowDays, restored.exportWindowDays)
  }

  // ── Helpers ──

  private suspend fun createTestGroup(groupId: String, myMemberId: String, name: String) {
    val rawKey = GroupKeyManager.generateGroupKey()
    val wrapped = GroupKeyManager.wrapKey(rawKey)
    repository.createGroup(AffinityGroupEntity(
      groupId = groupId,
      groupName = "$name's Group",
      createdAt = System.currentTimeMillis(),
      myMemberId = myMemberId,
      myDisplayName = name,
      groupKeyWrapped = wrapped,
      keyEpoch = 1,
      sharingConfigJson = GroupSharingConfig().toJson()
    ))
  }

  private fun makeMember(
    groupId: String,
    memberId: String,
    name: String,
    epoch: Int = 1
  ) = AffinityGroupMemberEntity(
    groupId = groupId,
    memberId = memberId,
    displayName = name,
    joinedAt = System.currentTimeMillis(),
    lastSeenEpoch = epoch,
    publicKeyBase64 = null,
    revoked = false
  )
}
