package ninja.unagi.group

import android.util.Base64
import ninja.unagi.data.AffinityGroupEntity
import ninja.unagi.data.AffinityGroupRepository
import ninja.unagi.data.AffinityImportLogEntity
import org.json.JSONObject
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Imports and decrypts .unagi bundles received from affinity group members.
 *
 * Fix #3: method named importBundle() instead of import() (Kotlin keyword).
 * Fix #4: enforces max payload size to prevent zip bombs.
 */
class BundleImporter(
  private val groupRepository: AffinityGroupRepository,
  private val dataMerger: DataMerger
) {
  companion object {
    /** Maximum allowed size for payload.enc (50 MB). */
    private const val MAX_PAYLOAD_BYTES = 50 * 1024 * 1024L
    /** Maximum allowed size for manifest.json (1 MB). */
    private const val MAX_MANIFEST_BYTES = 1 * 1024 * 1024L
  }

  /**
   * Import a .unagi bundle from an input stream. (Fix #3: renamed from import)
   */
  suspend fun importBundle(inputStream: InputStream): ImportResult {
    val (manifestJson, payloadBytes) = readZip(inputStream)
      ?: return ImportResult.Error("Invalid bundle: not a valid .unagi file")

    val manifest = try {
      BundleManifest.fromJson(manifestJson)
    } catch (e: Exception) {
      return ImportResult.Error("Invalid manifest: ${e.message}")
    }

    val group = groupRepository.getGroup(manifest.groupId)
      ?: return ImportResult.Error("Unknown group: ${manifest.groupId}. Join the group first.")

    if (groupRepository.hasImport(manifest.groupId, manifest.senderId, manifest.exportTimestamp)) {
      return ImportResult.Error("This bundle has already been imported.")
    }

    val payloadJson = try {
      decrypt(payloadBytes, group, manifest)
    } catch (e: Exception) {
      return ImportResult.Error("Decryption failed: ${e.message}")
    }

    val payload = try {
      BundleSerializer.deserializePayload(payloadJson)
    } catch (e: Exception) {
      return ImportResult.Error("Invalid payload: ${e.message}")
    }

    val mergeResult = dataMerger.merge(payload)

    groupRepository.logImport(
      AffinityImportLogEntity(
        groupId = manifest.groupId,
        senderId = manifest.senderId,
        exportTimestamp = manifest.exportTimestamp,
        importedAt = System.currentTimeMillis(),
        itemCounts = JSONObject().apply {
          put("devices", mergeResult.devicesAdded + mergeResult.devicesUpdated)
          put("sightings", mergeResult.sightingsAdded)
          put("alertRules", mergeResult.alertRulesAdded)
          put("enrichments", mergeResult.enrichmentsAdded)
        }.toString()
      )
    )

    return ImportResult.Success(manifest, mergeResult)
  }

  /**
   * Parse only the manifest from a bundle without decrypting, for preview purposes.
   */
  fun peekManifest(inputStream: InputStream): BundleManifest? {
    val (manifestJson, _) = readZip(inputStream) ?: return null
    return try {
      BundleManifest.fromJson(manifestJson)
    } catch (e: Exception) {
      null
    }
  }

  private fun decrypt(payloadBytes: ByteArray, group: AffinityGroupEntity, manifest: BundleManifest): String {
    val groupKey = GroupKeyManager.unwrapKey(group.groupKeyWrapped)
    // Fix #2: derive key with timestamp-specific info to match exporter
    val infoString = "unagi-bundle-encryption-${manifest.exportTimestamp}"
    val encKey = Hkdf.deriveKey(
      groupKey.encoded,
      salt = null,
      info = infoString.toByteArray(Charsets.UTF_8),
      length = 32
    )
    val nonce = Base64.decode(manifest.payloadNonce, Base64.NO_WRAP)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey, "AES"), GCMParameterSpec(128, nonce))
    return String(cipher.doFinal(payloadBytes), Charsets.UTF_8)
  }

  /**
   * Fix #4: reads ZIP with size limits to prevent zip bombs / OOM.
   */
  private fun readZip(inputStream: InputStream): Pair<String, ByteArray>? {
    var manifestJson: String? = null
    var payloadBytes: ByteArray? = null

    try {
      ZipInputStream(inputStream).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
          when (entry.name) {
            "manifest.json" -> {
              manifestJson = readLimited(zip, MAX_MANIFEST_BYTES)?.toString(Charsets.UTF_8)
            }
            "payload.enc" -> {
              payloadBytes = readLimited(zip, MAX_PAYLOAD_BYTES)
            }
          }
          zip.closeEntry()
          entry = zip.nextEntry
        }
      }
    } catch (e: Exception) {
      return null
    }

    if (manifestJson == null || payloadBytes == null) return null
    return manifestJson!! to payloadBytes!!
  }

  /**
   * Read from a stream with a size limit. Returns null if the limit is exceeded.
   */
  private fun readLimited(stream: InputStream, maxBytes: Long): ByteArray? {
    val buffer = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    var totalRead = 0L
    while (true) {
      val n = stream.read(chunk)
      if (n < 0) break
      totalRead += n
      if (totalRead > maxBytes) return null
      buffer.write(chunk, 0, n)
    }
    return buffer.toByteArray()
  }

  sealed class ImportResult {
    data class Success(val manifest: BundleManifest, val mergeResult: MergeResult) : ImportResult()
    data class Error(val message: String) : ImportResult()
  }
}

data class BundleManifest(
  val formatVersion: Int,
  val groupId: String,
  val senderId: String,
  val senderDisplayName: String,
  val exportTimestamp: Long,
  val keyEpoch: Int,
  val contentTypes: List<String>,
  val itemCounts: Map<String, Int>,
  val payloadNonce: String
) {
  companion object {
    fun fromJson(json: String): BundleManifest {
      val o = JSONObject(json)
      val contentTypes = mutableListOf<String>()
      val typesArr = o.getJSONArray("contentTypes")
      for (i in 0 until typesArr.length()) contentTypes.add(typesArr.getString(i))

      val itemCounts = mutableMapOf<String, Int>()
      val countsObj = o.getJSONObject("itemCounts")
      for (key in countsObj.keys()) itemCounts[key] = countsObj.getInt(key)

      return BundleManifest(
        formatVersion = o.getInt("formatVersion"),
        groupId = o.getString("groupId"),
        senderId = o.getString("senderId"),
        senderDisplayName = o.optString("senderDisplayName", "Unknown"),
        exportTimestamp = o.getLong("exportTimestamp"),
        keyEpoch = o.getInt("keyEpoch"),
        contentTypes = contentTypes,
        itemCounts = itemCounts,
        payloadNonce = o.getString("payloadNonce")
      )
    }
  }
}
