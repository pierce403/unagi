package ninja.unagi.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Manages the SQLCipher database passphrase, wrapped with Android Keystore.
 *
 * On first launch: generates a random 256-bit passphrase, wraps it with a Keystore
 * master key, and stores the wrapped passphrase in SharedPreferences.
 * On subsequent launches: unwraps the stored passphrase.
 */
object DatabaseKeyManager {
  private const val KEYSTORE_ALIAS = "unagi_db_wrapper"
  private const val ANDROID_KEYSTORE = "AndroidKeyStore"
  private const val GCM_TAG_LENGTH = 128
  private const val PREFS_NAME = "unagi_db_key"
  private const val PREF_WRAPPED_KEY = "wrapped_passphrase"

  fun getOrCreatePassphrase(context: Context): ByteArray {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val wrapped = prefs.getString(PREF_WRAPPED_KEY, null)

    if (wrapped != null) {
      return unwrapPassphrase(wrapped)
    }

    val passphrase = ByteArray(32)
    SecureRandom().nextBytes(passphrase)

    val wrappedBase64 = wrapPassphrase(passphrase)
    prefs.edit().putString(PREF_WRAPPED_KEY, wrappedBase64).apply()

    return passphrase
  }

  private fun wrapPassphrase(passphrase: ByteArray): String {
    val masterKey = getOrCreateMasterKey()
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, masterKey)
    val ciphertext = cipher.doFinal(passphrase)
    val combined = cipher.iv + ciphertext
    return Base64.encodeToString(combined, Base64.NO_WRAP)
  }

  private fun unwrapPassphrase(wrappedBase64: String): ByteArray {
    val combined = Base64.decode(wrappedBase64, Base64.NO_WRAP)
    val iv = combined.copyOfRange(0, 12)
    val ciphertext = combined.copyOfRange(12, combined.size)
    val masterKey = getOrCreateMasterKey()
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
    return cipher.doFinal(ciphertext)
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
