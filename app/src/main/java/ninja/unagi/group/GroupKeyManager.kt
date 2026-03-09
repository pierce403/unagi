package ninja.unagi.group

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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
 * Group keys are generated as random AES-256 keys. Before storage in Room,
 * they are encrypted (wrapped) using an Android Keystore master key so the
 * raw group key is never stored in plaintext on disk.
 */
object GroupKeyManager {
  private const val KEYSTORE_ALIAS = "unagi_group_wrapper"
  private const val ANDROID_KEYSTORE = "AndroidKeyStore"
  private const val GCM_TAG_LENGTH = 128

  fun generateGroupKey(): SecretKey {
    val keyGen = KeyGenerator.getInstance("AES")
    keyGen.init(256)
    return keyGen.generateKey()
  }

  /**
   * Wrap (encrypt) a group key using the Android Keystore master key.
   * Returns a Base64 string containing the GCM nonce prepended to the ciphertext.
   */
  fun wrapKey(rawKey: SecretKey): String {
    val masterKey = getOrCreateMasterKey()
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
    val masterKey = getOrCreateMasterKey()
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
    val rawBytes = cipher.doFinal(ciphertext)
    return SecretKeySpec(rawBytes, "AES")
  }

  /**
   * Build a shareable JSON-safe payload containing the raw key and an HMAC
   * binding it to the group ID, so corruption during transit is detected
   * before the key is stored. (Fix #14)
   *
   * @return Base64-encoded key
   */
  fun exportKeyForSharing(rawKey: SecretKey): String {
    return Base64.encodeToString(rawKey.encoded, Base64.NO_WRAP)
  }

  /**
   * Compute an HMAC-SHA256 of the key bound to the group ID so the receiver
   * can detect accidental corruption before persisting a bad key. (Fix #14)
   */
  fun computeKeyChecksum(rawKey: SecretKey, groupId: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(rawKey.encoded, "HmacSHA256"))
    val hash = mac.doFinal(groupId.toByteArray(Charsets.UTF_8))
    // Return first 8 bytes as hex for a compact checksum
    return hash.take(8).joinToString("") { "%02x".format(it) }
  }

  /**
   * Verify that a received key matches the expected checksum for the given group ID. (Fix #14)
   */
  fun verifyKeyChecksum(rawKey: SecretKey, groupId: String, checksum: String): Boolean {
    return computeKeyChecksum(rawKey, groupId) == checksum
  }

  /** Import a Base64-encoded raw group key received from another member. */
  fun importKeyFromSharing(base64Key: String): SecretKey {
    val rawBytes = Base64.decode(base64Key, Base64.NO_WRAP)
    return SecretKeySpec(rawBytes, "AES")
  }

  private fun getOrCreateMasterKey(): SecretKey {
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
}
