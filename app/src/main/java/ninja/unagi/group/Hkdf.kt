package ninja.unagi.group

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA256 implementation (RFC 5869) for deriving subkeys from a group key.
 * Used to derive per-export encryption keys from the shared group secret.
 *
 * Note on key material zeroing (review item #13): On the JVM, ByteArray contents
 * cannot be reliably zeroed because the GC may copy objects during compaction.
 * For defense-in-depth on Android, consider using the NDK for key derivation in
 * a future iteration. The current approach is standard for Android Kotlin apps.
 */
object Hkdf {
  private const val HASH_LEN = 32

  fun extract(salt: ByteArray?, ikm: ByteArray): ByteArray {
    val effectiveSalt = salt ?: ByteArray(HASH_LEN)
    return hmacSha256(effectiveSalt, ikm)
  }

  fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
    require(length <= 255 * HASH_LEN) { "Output length too large" }
    val n = (length + HASH_LEN - 1) / HASH_LEN
    var t = ByteArray(0)
    val okm = ByteArray(length)
    var offset = 0
    for (i in 1..n) {
      t = hmacSha256(prk, t + info + byteArrayOf(i.toByte()))
      val copyLen = minOf(HASH_LEN, length - offset)
      t.copyInto(okm, offset, 0, copyLen)
      offset += copyLen
    }
    return okm
  }

  /**
   * Extract-then-expand in one call.
   * @param ikm Input keying material (e.g. raw group key bytes)
   * @param salt Optional salt
   * @param info Context bytes -- MUST be unique per encryption operation (fix #2)
   * @param length Desired output length
   */
  fun deriveKey(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
    val prk = extract(salt, ikm)
    return expand(prk, info, length)
  }

  private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
  }
}
