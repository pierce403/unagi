package ninja.unagi.group

import android.util.Base64
import ninja.unagi.data.AffinityGroupEntity
import ninja.unagi.data.AffinityGroupMemberEntity
import ninja.unagi.data.AlertRuleDao
import ninja.unagi.data.DeviceDao
import ninja.unagi.data.DeviceEnrichmentDao
import ninja.unagi.data.SightingDao
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Exports local data as an encrypted .unagi bundle for sharing with affinity group members.
 *
 * Supports two encryption modes:
 * - Symmetric: derives encryption key from shared group key via HKDF (legacy/default)
 * - Hybrid ECDH: generates random CEK, wraps it per-recipient via ECDH key agreement
 *
 * When requireEcdh is true on the group, only ECDH mode is used — no symmetric fallback.
 */
class BundleExporter(
  private val deviceDao: DeviceDao,
  private val sightingDao: SightingDao,
  private val alertRuleDao: AlertRuleDao,
  private val enrichmentDao: DeviceEnrichmentDao
) {
  suspend fun export(
    group: AffinityGroupEntity,
    config: GroupSharingConfig,
    members: List<AffinityGroupMemberEntity> = emptyList()
  ): ByteArray {
    val exportTimestamp = System.currentTimeMillis()
    val windowMs = config.exportWindowDays.toLong() * 24 * 60 * 60 * 1000
    val windowStart = exportTimestamp - windowMs

    val devices = if (config.devices) deviceDao.getDevices() else emptyList()
    val sightings = if (config.sightings) sightingDao.getSightingsAfter(windowStart) else emptyList()
    val alertRules = if (config.alertRules) alertRuleDao.getRules() else emptyList()
    val enrichments = if (config.enrichments) enrichmentDao.getEnrichments() else emptyList()
    val starredKeys = if (config.starredDevices) {
      devices.filter { it.starred }.map { it.deviceKey }
    } else emptyList()

    val payloadJson = BundleSerializer.serializePayload(
      devices, sightings, alertRules, enrichments, starredKeys
    )
    val payloadBytes = payloadJson.toByteArray(Charsets.UTF_8)

    // Generate random content encryption key (CEK)
    val cek = ByteArray(32)
    SecureRandom().nextBytes(cek)

    // Encrypt payload with CEK
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"))
    val ciphertext = cipher.doFinal(payloadBytes)
    val payloadNonce = cipher.iv

    val contentTypes = JSONArray()
    if (config.devices) contentTypes.put("devices")
    if (config.sightings) contentTypes.put("sightings")
    if (config.alertRules) contentTypes.put("alertRules")
    if (config.enrichments) contentTypes.put("enrichments")
    if (config.starredDevices) contentTypes.put("starredDevices")

    val itemCounts = JSONObject().apply {
      put("devices", devices.size)
      put("sightings", sightings.size)
      put("alertRules", alertRules.size)
      put("enrichments", enrichments.size)
      put("starredDeviceKeys", starredKeys.size)
    }

    val manifest = JSONObject().apply {
      put("formatVersion", 2)
      put("type", "affinity-bundle")
      put("groupId", group.groupId)
      put("senderId", group.myMemberId)
      put("senderDisplayName", group.myDisplayName)
      put("exportTimestamp", exportTimestamp)
      put("keyEpoch", group.keyEpoch)
      put("contentTypes", contentTypes)
      put("itemCounts", itemCounts)
      put("payloadNonce", Base64.encodeToString(payloadNonce, Base64.NO_WRAP))
    }

    // P6: include sender's public key in manifest for ECDH
    val senderPublicKey = EcdhKeyManager.getPublicKey(group.groupId, group.myMemberId)
    if (senderPublicKey != null) {
      manifest.put("senderPublicKey", senderPublicKey)
    }

    // Wrap CEK for each eligible recipient via ECDH
    val recipientKeys = buildRecipientKeys(group, members, cek, exportTimestamp)
    if (recipientKeys.length() > 0) {
      manifest.put("recipientKeys", recipientKeys)
    }

    // Symmetric CEK wrap (unless requireEcdh is true)
    if (!group.requireEcdh) {
      val groupKey = GroupKeyManager.unwrapKey(group.groupKeyWrapped)
      val infoString = "unagi-bundle-cek-wrap-${exportTimestamp}"
      val wrapKey = Hkdf.deriveKey(
        groupKey.encoded, salt = null,
        info = infoString.toByteArray(Charsets.UTF_8), length = 32
      )
      val wrapCipher = Cipher.getInstance("AES/GCM/NoPadding")
      wrapCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(wrapKey, "AES"))
      val wrappedCek = wrapCipher.doFinal(cek)
      manifest.put("symmetricCekNonce", Base64.encodeToString(wrapCipher.iv, Base64.NO_WRAP))
      manifest.put("symmetricCekWrapped", Base64.encodeToString(wrappedCek, Base64.NO_WRAP))
    }

    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zip ->
      zip.putNextEntry(ZipEntry("manifest.json"))
      zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
      zip.closeEntry()

      zip.putNextEntry(ZipEntry("payload.enc"))
      zip.write(ciphertext)
      zip.closeEntry()
    }

    return baos.toByteArray()
  }

  /**
   * Build per-recipient ECDH-wrapped CEK entries.
   * Only includes non-revoked members who have a public key.
   */
  private fun buildRecipientKeys(
    group: AffinityGroupEntity,
    members: List<AffinityGroupMemberEntity>,
    cek: ByteArray,
    exportTimestamp: Long
  ): JSONArray {
    val recipientKeys = JSONArray()
    val eligibleMembers = members.filter { !it.revoked && it.publicKeyBase64 != null && it.memberId != group.myMemberId }

    for (member in eligibleMembers) {
      try {
        val info = "unagi-ecdh-cek-wrap-${group.groupId}-${exportTimestamp}".toByteArray(Charsets.UTF_8)
        val sharedSecret = EcdhKeyManager.deriveSharedSecret(
          group.groupId, group.myMemberId, member.publicKeyBase64!!, info
        )
        val wrapCipher = Cipher.getInstance("AES/GCM/NoPadding")
        wrapCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sharedSecret, "AES"))
        val wrappedCek = wrapCipher.doFinal(cek)

        recipientKeys.put(JSONObject().apply {
          put("memberId", member.memberId)
          put("wrappedCek", Base64.encodeToString(wrappedCek, Base64.NO_WRAP))
          put("nonce", Base64.encodeToString(wrapCipher.iv, Base64.NO_WRAP))
        })
      } catch (_: Exception) {
        // Skip members where ECDH fails (stale key, missing keypair, etc.)
      }
    }

    return recipientKeys
  }

  companion object {
    fun defaultFileName(groupName: String): String {
      val sanitized = groupName
        .trim()
        .replace(Regex("[^a-zA-Z0-9_-]"), "_")
        .take(32)
        .ifEmpty { "group" }
      return "unagi-${sanitized}-${System.currentTimeMillis()}.unagi"
    }
  }
}
