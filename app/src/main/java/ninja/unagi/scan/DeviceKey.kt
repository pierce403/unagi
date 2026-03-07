package ninja.unagi.scan

import java.security.MessageDigest

object DeviceKey {
  fun from(input: ObservationInput): String {
    val token = when {
      input.manufacturerData.isNotEmpty() -> {
        val parts = input.manufacturerData.toSortedMap().map { (id, data) ->
          "$id:$data"
        }
        "m:" + parts.joinToString("|")
      }
      input.serviceUuids.isNotEmpty() -> {
        "s:" + input.serviceUuids.sorted().joinToString("|")
      }
      !input.address.isNullOrBlank() -> "a:${input.address}"
      !input.name.isNullOrBlank() -> "n:${input.name}"
      else -> "unknown"
    }

    return sha256(token)
  }

  private fun sha256(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(value.toByteArray())
    return bytes.joinToString("") { b -> "%02x".format(b) }
  }
}
