package ninja.unagi.scan

import java.security.MessageDigest

object DeviceKey {
  fun from(input: ObservationInput): String {
    val token = when {
      !input.normalizedAddress.isNullOrBlank() -> "a:${input.normalizedAddress}"
      !input.address.isNullOrBlank() -> "a:${input.address}"
      !input.name.isNullOrBlank() -> "n:${input.name}"
      else -> buildFallbackToken(input)
    }

    return sha256(token)
  }

  private fun buildFallbackToken(input: ObservationInput): String {
    return buildString {
      append("volatile:")
      append(input.source.lowercase())
      append(':')
      append(input.timestamp)
      append(':')
      append(input.rssi)
      append(':')
      append(input.classificationFingerprint ?: "none")
      append(':')
      append(input.serviceUuids.size)
      append(':')
      append(input.manufacturerData.size)
    }
  }

  private fun sha256(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(value.toByteArray())
    return bytes.joinToString("") { b -> "%02x".format(b) }
  }
}
