package ninja.unagi.group

import android.app.KeyguardManager
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import ninja.unagi.data.AffinityGroupEntity
import ninja.unagi.data.AffinityGroupRepository
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Manages AES-256 group keys with Android Keystore wrapping.
 *
 * V1 alias: unauthenticated (legacy). V2 alias: requires device credential within 5 minutes.
 * On first launch after upgrade, all group keys are re-wrapped from V1 to V2 if device is secure.
 * Per pitfall P1: skips V2 on devices without lock screen.
 * Per pitfall P2: uses SharedPreferences flag for crash-safe migration.
 */
object GroupKeyManager {
  private const val KEYSTORE_ALIAS = "unagi_group_wrapper"
  private const val KEYSTORE_ALIAS_V2 = "unagi_group_wrapper_v2"
  private const val ANDROID_KEYSTORE = "AndroidKeyStore"
  private const val GCM_TAG_LENGTH = 128
  private const val TAG = "GroupKeyManager"
  private const val PREFS_NAME = "unagi_keystore_migration"
  private const val PREF_MIGRATION_COMPLETE = "keystore_v2_migration_complete"

  private var useV2 = false

  fun generateGroupKey(): SecretKey {
    val keyGen = KeyGenerator.getInstance("AES")
    keyGen.init(256)
    return keyGen.generateKey()
  }

  /**
   * Wrap (encrypt) a group key using the active Keystore master key.
   * Returns a Base64 string containing the GCM nonce prepended to the ciphertext.
   */
  fun wrapKey(rawKey: SecretKey): String {
    val masterKey = getActiveMasterKey()
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, masterKey)
    val ciphertext = cipher.doFinal(rawKey.encoded)
    val combined = cipher.iv + ciphertext
    return Base64.encodeToString(combined, Base64.NO_WRAP)
  }

  /**
   * Unwrap (decrypt) a group key previously wrapped with [wrapKey].
   */
  fun unwrapKey(wrappedBase64: String): SecretKey {
    val combined = Base64.decode(wrappedBase64, Base64.NO_WRAP)
    val iv = combined.copyOfRange(0, 12)
    val ciphertext = combined.copyOfRange(12, combined.size)
    val masterKey = getActiveMasterKey()
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
    val rawBytes = cipher.doFinal(ciphertext)
    return SecretKeySpec(rawBytes, "AES")
  }

  fun exportKeyForSharing(rawKey: SecretKey): String {
    return Base64.encodeToString(rawKey.encoded, Base64.NO_WRAP)
  }

  fun computeKeyChecksum(rawKey: SecretKey, groupId: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(rawKey.encoded, "HmacSHA256"))
    val hash = mac.doFinal(groupId.toByteArray(Charsets.UTF_8))
    return hash.take(8).joinToString("") { "%02x".format(it) }
  }

  fun verifyKeyChecksum(rawKey: SecretKey, groupId: String, checksum: String): Boolean {
    return computeKeyChecksum(rawKey, groupId) == checksum
  }

  fun importKeyFromSharing(base64Key: String): SecretKey {
    val rawBytes = Base64.decode(base64Key, Base64.NO_WRAP)
    return SecretKeySpec(rawBytes, "AES")
  }

  /**
   * Generate a new group key and wrap it. Used for key rotation on member revocation.
   * Returns the new wrapped key string.
   */
  fun rotateGroupKey(): Pair<SecretKey, String> {
    val newKey = generateGroupKey()
    val wrapped = wrapKey(newKey)
    return newKey to wrapped
  }

  /**
   * Migrate group keys from V1 (unauthenticated) to V2 (auth-required) Keystore alias.
   * Per P1: skips if device has no lock screen.
   * Per P2: uses SharedPreferences flag for crash-safe idempotent migration.
   * Per P3: must be called after DB is open (after SQLCipher migration).
   */
  suspend fun migrateToV2IfNeeded(context: Context, repository: AffinityGroupRepository) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    if (prefs.getBoolean(PREF_MIGRATION_COMPLETE, false)) {
      useV2 = hasV2Key()
      return
    }

    val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    if (!keyguard.isDeviceSecure) {
      Log.i(TAG, "No secure lock screen — skipping V2 Keystore migration")
      prefs.edit().putBoolean(PREF_MIGRATION_COMPLETE, true).apply()
      return
    }

    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
    keyStore.load(null)

    if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
      // No V1 key means fresh install — create V2 directly
      createV2MasterKey()
      useV2 = true
      prefs.edit().putBoolean(PREF_MIGRATION_COMPLETE, true).apply()
      return
    }

    Log.i(TAG, "Starting V1 → V2 Keystore migration")
    try {
      createV2MasterKey()

      val groups = repository.getGroups()
      for (group in groups) {
        val rawKey = unwrapWithV1(group.groupKeyWrapped)
        val newWrapped = wrapWithV2(rawKey)
        repository.updateGroup(group.copy(groupKeyWrapped = newWrapped))
      }

      keyStore.deleteEntry(KEYSTORE_ALIAS)
      useV2 = true
      prefs.edit().putBoolean(PREF_MIGRATION_COMPLETE, true).apply()
      Log.i(TAG, "V1 → V2 migration complete, ${groups.size} groups re-wrapped")
    } catch (e: Exception) {
      Log.e(TAG, "V2 migration failed — will retry next launch", e)
      // Don't set flag — retry on next launch
    }
  }

  private fun getActiveMasterKey(): SecretKey {
    if (useV2) return getV2MasterKey()
    return getOrCreateV1MasterKey()
  }

  private fun getOrCreateV1MasterKey(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
    keyStore.load(null)
    val existing = keyStore.getKey(KEYSTORE_ALIAS, null)
    if (existing != null) return existing as SecretKey

    val spec = KeyGenParameterSpec.Builder(
      KEYSTORE_ALIAS,
      KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
    )
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setKeySize(256)
      .build()

    val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    keyGen.init(spec)
    return keyGen.generateKey()
  }

  private fun createV2MasterKey() {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
    keyStore.load(null)
    if (keyStore.containsAlias(KEYSTORE_ALIAS_V2)) return

    val spec = KeyGenParameterSpec.Builder(
      KEYSTORE_ALIAS_V2,
      KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
    )
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setKeySize(256)
      .setUserAuthenticationRequired(true)
      .setUserAuthenticationValidityDurationSeconds(300)
      .build()

    val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    keyGen.init(spec)
    keyGen.generateKey()
  }

  private fun getV2MasterKey(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
    keyStore.load(null)
    return keyStore.getKey(KEYSTORE_ALIAS_V2, null) as SecretKey
  }

  private fun hasV2Key(): Boolean {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
    keyStore.load(null)
    return keyStore.containsAlias(KEYSTORE_ALIAS_V2)
  }

  private fun unwrapWithV1(wrappedBase64: String): SecretKey {
    val combined = Base64.decode(wrappedBase64, Base64.NO_WRAP)
    val iv = combined.copyOfRange(0, 12)
    val ciphertext = combined.copyOfRange(12, combined.size)
    val masterKey = getOrCreateV1MasterKey()
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
    val rawBytes = cipher.doFinal(ciphertext)
    return SecretKeySpec(rawBytes, "AES")
  }

  private fun wrapWithV2(rawKey: SecretKey): String {
    val masterKey = getV2MasterKey()
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, masterKey)
    val ciphertext = cipher.doFinal(rawKey.encoded)
    val combined = cipher.iv + ciphertext
    return Base64.encodeToString(combined, Base64.NO_WRAP)
  }
}
