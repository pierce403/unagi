package ninja.unagi.scan

import java.security.MessageDigest

object DeviceKey {
  // Note: BLE randomized addresses and classic public MACs for the same physical device
  // produce separate keys. Cross-transport merge is not yet implemented.
  fun from(input: ObservationInput): String {
    val token = when {
      !input.normalizedAddress.isNullOrBlank() -> "a:${input.normalizedAddress}"
      !input.address.isNullOrBlank() -> "a:${input.address}"
      !input.name.isNullOrBlank() -> "n:${input.name}"
      else -> buildFallbackToken(input)
    }

    return synchronized(cache) {
      cache[token] ?: sha256(token).also { hash -> cache[token] = hash }
    }
  }

  // Uses only stable signal data (no timestamp/rssi) so unnamed devices with the same
  // radio fingerprint consolidate into one key instead of creating unbounded spam.
  private fun buildFallbackToken(input: ObservationInput): String {
    return buildString {
      append("volatile:")
      append(input.source.lowercase())
      append(':')
      append(input.classificationFingerprint ?: "none")
      append(':')
      append(input.serviceUuids.sorted().joinToString(",").ifEmpty { "no-services" })
      append(':')
      append(input.manufacturerData.keys.sorted().joinToString(",").ifEmpty { "no-mfg" })
    }
  }

  private fun sha256(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(value.toByteArray())
    val chars = CharArray(bytes.size * 2)
    bytes.forEachIndexed { index, byte ->
      val unsigned = byte.toInt() and 0xFF
      chars[index * 2] = HEX_CHARS[unsigned ushr 4]
      chars[index * 2 + 1] = HEX_CHARS[unsigned and 0x0F]
    }
    return String(chars)
  }

  private val HEX_CHARS = "0123456789abcdef".toCharArray()
  private const val MAX_CACHE_ENTRIES = 4_096
  private val cache = object : LinkedHashMap<String, String>(MAX_CACHE_ENTRIES, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
      return size > MAX_CACHE_ENTRIES
    }
  }
}
