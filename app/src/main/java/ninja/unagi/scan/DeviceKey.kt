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

    return sha256(token)
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
    return bytes.joinToString("") { b -> "%02x".format(b) }
  }
}
