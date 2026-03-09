package ninja.unagi.group

import android.util.Base64
import ninja.unagi.data.AffinityGroupEntity
import ninja.unagi.data.AlertRuleDao
import ninja.unagi.data.DeviceDao
import ninja.unagi.data.DeviceEnrichmentDao
import ninja.unagi.data.SightingDao
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Exports local data as an encrypted .unagi bundle for sharing with affinity group members.
 *
 * Fix #2: includes exportTimestamp in HKDF info to derive a unique encryption key per export.
 * Fix #7: exports all data (no incremental yet -- tracked as a future enhancement).
 */
class BundleExporter(
  private val deviceDao: DeviceDao,
  private val sightingDao: SightingDao,
  private val alertRuleDao: AlertRuleDao,
  private val enrichmentDao: DeviceEnrichmentDao
) {
  suspend fun export(group: AffinityGroupEntity, config: GroupSharingConfig): ByteArray {
    val exportTimestamp = System.currentTimeMillis()

    val devices = if (config.devices) deviceDao.getDevices() else emptyList()
    val sightings = if (config.sightings) sightingDao.getSightings() else emptyList()
    val alertRules = if (config.alertRules) alertRuleDao.getRules() else emptyList()
    val enrichments = if (config.enrichments) enrichmentDao.getEnrichments() else emptyList()
    val starredKeys = if (config.starredDevices) {
      devices.filter { it.starred }.map { it.deviceKey }
    } else emptyList()

    val payloadJson = BundleSerializer.serializePayload(
      devices, sightings, alertRules, enrichments, starredKeys
    )

    // Fix #2: derive a unique encryption key per export by including timestamp in HKDF info
    val groupKey = GroupKeyManager.unwrapKey(group.groupKeyWrapped)
    val infoString = "unagi-bundle-encryption-${exportTimestamp}"
    val encKey = Hkdf.deriveKey(
      groupKey.encoded,
      salt = null,
      info = infoString.toByteArray(Charsets.UTF_8),
      length = 32
    )

    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey, "AES"))
    val ciphertext = cipher.doFinal(payloadJson.toByteArray(Charsets.UTF_8))
    val nonce = cipher.iv

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
      put("formatVersion", 1)
      put("type", "affinity-bundle")
      put("groupId", group.groupId)
      put("senderId", group.myMemberId)
      put("senderDisplayName", group.myDisplayName)
      put("exportTimestamp", exportTimestamp)
      put("keyEpoch", group.keyEpoch)
      put("contentTypes", contentTypes)
      put("itemCounts", itemCounts)
      put("payloadNonce", Base64.encodeToString(nonce, Base64.NO_WRAP))
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

  companion object {
    fun defaultFileName(groupName: String): String {
      val sanitized = groupName
        .trim()
        .replace(Regex("[^a-zA-Z0-9_-]"), "_")
        .take(32)
        .ifEmpty { "group" }
      // Fix #20: use millis for consistency with exportTimestamp
      return "unagi-${sanitized}-${System.currentTimeMillis()}.unagi"
    }
  }
}
