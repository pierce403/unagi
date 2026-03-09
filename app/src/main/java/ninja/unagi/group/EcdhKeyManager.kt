package ninja.unagi.group

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/**
 * Manages P-256 ECDH keypairs for hybrid bundle encryption.
 *
 * Private keys are stored in Android Keystore (alias: unagi_ecdh_{groupId}_{memberId}).
 * Public keys are stored as Base64 in AffinityGroupMemberEntity.publicKeyBase64.
 *
 * Per P6: bundle manifest includes senderPublicKey so importer doesn't rely on stored key.
 * Per P7: key checksum binds public key to group secret.
 * Per P9: deleteKeypair() cleans up Keystore entries on group deletion.
 */
object EcdhKeyManager {
  private const val ANDROID_KEYSTORE = "AndroidKeyStore"

  private fun aliasFor(groupId: String, memberId: String): String =
    "unagi_ecdh_${groupId}_${memberId}"

  /**
   * Generate a P-256 ECDH keypair in Keystore and return the public key as Base64.
   */
  fun generateKeypair(groupId: String, memberId: String): String {
    val alias = aliasFor(groupId, memberId)
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
    keyStore.load(null)

    if (keyStore.containsAlias(alias)) {
      val cert = keyStore.getCertificate(alias)
      return Base64.encodeToString(cert.publicKey.encoded, Base64.NO_WRAP)
    }

    val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_AGREE_KEY)
      .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
      .build()

    val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
    kpg.initialize(spec)
    val keyPair = kpg.generateKeyPair()

    return Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
  }

  /**
   * Perform ECDH key agreement between our private key and the peer's public key.
   * Returns a 32-byte shared secret derived via HKDF.
   */
  fun deriveSharedSecret(
    groupId: String,
    memberId: String,
    peerPublicKeyBase64: String,
    info: ByteArray
  ): ByteArray {
    val alias = aliasFor(groupId, memberId)
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
    keyStore.load(null)

    val privateKey = keyStore.getKey(alias, null)
      ?: throw IllegalStateException("No ECDH private key for $alias")

    val peerKeyBytes = Base64.decode(peerPublicKeyBase64, Base64.NO_WRAP)
    val keyFactory = KeyFactory.getInstance("EC")
    val peerPublicKey = keyFactory.generatePublic(X509EncodedKeySpec(peerKeyBytes))

    val keyAgreement = KeyAgreement.getInstance("ECDH")
    keyAgreement.init(privateKey)
    keyAgreement.doPhase(peerPublicKey, true)
    val rawSecret = keyAgreement.generateSecret()

    return Hkdf.deriveKey(rawSecret, salt = null, info = info, length = 32)
  }

  /**
   * Derive shared secret from two raw public keys (for wrapping CEK to recipients).
   * Used by the exporter who holds the private key in Keystore.
   */
  fun getPublicKey(groupId: String, memberId: String): String? {
    val alias = aliasFor(groupId, memberId)
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
    keyStore.load(null)
    val cert = keyStore.getCertificate(alias) ?: return null
    return Base64.encodeToString(cert.publicKey.encoded, Base64.NO_WRAP)
  }

  fun hasKeypair(groupId: String, memberId: String): Boolean {
    val alias = aliasFor(groupId, memberId)
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
    keyStore.load(null)
    return keyStore.containsAlias(alias)
  }

  /** P9: Clean up Keystore entries when a group is deleted. */
  fun deleteKeypair(groupId: String, memberId: String) {
    val alias = aliasFor(groupId, memberId)
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
    keyStore.load(null)
    if (keyStore.containsAlias(alias)) {
      keyStore.deleteEntry(alias)
    }
  }
}
