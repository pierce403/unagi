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
 * Supports two decryption modes:
 * - ECDH hybrid: uses sender's public key + own private key to unwrap CEK
 * - Symmetric fallback: derives CEK unwrap key from shared group key via HKDF
 *
 * When group.requireEcdh is true, rejects bundles that lack a recipientKeys entry
 * for the local member — no silent downgrade to symmetric.
 */
class BundleImporter(
  private val groupRepository: AffinityGroupRepository,
  private val dataMerger: DataMerger
) {
  companion object {
    private const val MAX_PAYLOAD_BYTES = 50 * 1024 * 1024L
    private const val MAX_MANIFEST_BYTES = 1 * 1024 * 1024L
  }

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

    // Epoch validation: reject bundles with keyEpoch < local epoch (stale/revoked sender)
    if (manifest.keyEpoch < group.keyEpoch) {
      return ImportResult.Error("Bundle uses outdated key epoch ${manifest.keyEpoch} (current: ${group.keyEpoch}). Sender may need to update their group key.")
    }

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

    val mergeResult = dataMerger.merge(payload, manifest.groupId)

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

  fun peekManifest(inputStream: InputStream): BundleManifest? {
    val (manifestJson, _) = readZip(inputStream) ?: return null
    return try {
      BundleManifest.fromJson(manifestJson)
    } catch (e: Exception) {
      null
    }
  }

  private fun decrypt(payloadBytes: ByteArray, group: AffinityGroupEntity, manifest: BundleManifest): String {
    val cek = unwrapCek(group, manifest)
    val nonce = Base64.decode(manifest.payloadNonce, Base64.NO_WRAP)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
    return String(cipher.doFinal(payloadBytes), Charsets.UTF_8)
  }

  /**
   * Unwrap the content encryption key. Tries ECDH first, falls back to symmetric
   * (unless requireEcdh is enabled on the group).
   */
  private fun unwrapCek(group: AffinityGroupEntity, manifest: BundleManifest): ByteArray {
    // Try ECDH path first
    val ecdhCek = tryEcdhUnwrap(group, manifest)
    if (ecdhCek != null) return ecdhCek

    // If requireEcdh, reject symmetric fallback
    if (group.requireEcdh) {
      throw IllegalStateException("ECDH required but no recipientKeys entry for this member")
    }

    // Symmetric fallback (format v1 or v2 with symmetric CEK wrap)
    return symmetricUnwrapCek(group, manifest)
  }

  private fun tryEcdhUnwrap(group: AffinityGroupEntity, manifest: BundleManifest): ByteArray? {
    val recipientEntry = manifest.recipientKeys.find { it.memberId == group.myMemberId }
      ?: return null

    val senderPublicKey = manifest.senderPublicKey ?: return null

    if (!EcdhKeyManager.hasKeypair(group.groupId, group.myMemberId)) return null

    val info = "unagi-ecdh-cek-wrap-${group.groupId}-${manifest.exportTimestamp}".toByteArray(Charsets.UTF_8)
    val sharedSecret = EcdhKeyManager.deriveSharedSecret(
      group.groupId, group.myMemberId, senderPublicKey, info
    )

    val wrappedCek = Base64.decode(recipientEntry.wrappedCek, Base64.NO_WRAP)
    val nonce = Base64.decode(recipientEntry.nonce, Base64.NO_WRAP)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sharedSecret, "AES"), GCMParameterSpec(128, nonce))
    return cipher.doFinal(wrappedCek)
  }

  private fun symmetricUnwrapCek(group: AffinityGroupEntity, manifest: BundleManifest): ByteArray {
    val groupKey = GroupKeyManager.unwrapKey(group.groupKeyWrapped)

    // Format v2: CEK wrapped separately
    if (manifest.formatVersion >= 2 && manifest.symmetricCekNonce != null && manifest.symmetricCekWrapped != null) {
      val infoString = "unagi-bundle-cek-wrap-${manifest.exportTimestamp}"
      val wrapKey = Hkdf.deriveKey(
        groupKey.encoded, salt = null,
        info = infoString.toByteArray(Charsets.UTF_8), length = 32
      )
      val wrappedCek = Base64.decode(manifest.symmetricCekWrapped, Base64.NO_WRAP)
      val nonce = Base64.decode(manifest.symmetricCekNonce, Base64.NO_WRAP)
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(wrapKey, "AES"), GCMParameterSpec(128, nonce))
      return cipher.doFinal(wrappedCek)
    }

    // Format v1: payload encrypted directly with HKDF-derived key (no CEK separation)
    // Return the derived key as the "CEK" — decrypt will use it directly
    val infoString = "unagi-bundle-encryption-${manifest.exportTimestamp}"
    return Hkdf.deriveKey(
      groupKey.encoded, salt = null,
      info = infoString.toByteArray(Charsets.UTF_8), length = 32
    )
  }

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

data class RecipientKeyEntry(
  val memberId: String,
  val wrappedCek: String,
  val nonce: String
)

data class BundleManifest(
  val formatVersion: Int,
  val groupId: String,
  val senderId: String,
  val senderDisplayName: String,
  val exportTimestamp: Long,
  val keyEpoch: Int,
  val contentTypes: List<String>,
  val itemCounts: Map<String, Int>,
  val payloadNonce: String,
  val senderPublicKey: String? = null,
  val recipientKeys: List<RecipientKeyEntry> = emptyList(),
  val symmetricCekNonce: String? = null,
  val symmetricCekWrapped: String? = null
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

      val recipientKeys = mutableListOf<RecipientKeyEntry>()
      if (o.has("recipientKeys")) {
        val arr = o.getJSONArray("recipientKeys")
        for (i in 0 until arr.length()) {
          val entry = arr.getJSONObject(i)
          recipientKeys.add(RecipientKeyEntry(
            memberId = entry.getString("memberId"),
            wrappedCek = entry.getString("wrappedCek"),
            nonce = entry.getString("nonce")
          ))
        }
      }

      return BundleManifest(
        formatVersion = o.optInt("formatVersion", 1),
        groupId = o.getString("groupId"),
        senderId = o.getString("senderId"),
        senderDisplayName = o.optString("senderDisplayName", "Unknown"),
        exportTimestamp = o.getLong("exportTimestamp"),
        keyEpoch = o.getInt("keyEpoch"),
        contentTypes = contentTypes,
        itemCounts = itemCounts,
        payloadNonce = o.getString("payloadNonce"),
        senderPublicKey = if (o.has("senderPublicKey")) o.getString("senderPublicKey") else null,
        recipientKeys = recipientKeys,
        symmetricCekNonce = if (o.has("symmetricCekNonce")) o.getString("symmetricCekNonce") else null,
        symmetricCekWrapped = if (o.has("symmetricCekWrapped")) o.getString("symmetricCekWrapped") else null
      )
    }
  }
}
