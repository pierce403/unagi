package ninja.unagi.util

object BluetoothNameSignatures {
  const val KARR_NAME_PREFIX = "QT "
  const val KARR_HINT = "Possible KARR backdoor naming pattern"

  fun matchesPrefix(name: String?, prefix: String): Boolean {
    if (prefix.isEmpty()) {
      return false
    }

    val candidate = name?.takeIf { it.isNotEmpty() } ?: return false
    return candidate.startsWith(prefix, ignoreCase = true)
  }

  fun matchesPrefixWithSuffix(name: String?, prefix: String): Boolean {
    if (!matchesPrefix(name, prefix)) {
      return false
    }

    return name?.drop(prefix.length)?.isNotBlank() == true
  }

  fun matchesKarrBackdoor(name: String?): Boolean {
    return matchesPrefixWithSuffix(name, KARR_NAME_PREFIX)
  }
}
