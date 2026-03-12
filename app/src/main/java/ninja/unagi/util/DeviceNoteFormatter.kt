package ninja.unagi.util

object DeviceNoteFormatter {
  const val MAX_LENGTH = 20

  fun normalize(raw: CharSequence?): String? {
    return raw
      ?.toString()
      ?.trim()
      ?.take(MAX_LENGTH)
      ?.takeIf(String::isNotEmpty)
  }

  fun appendToTitle(title: String, note: String?): String {
    val normalizedNote = normalize(note) ?: return title
    return "$title ($normalizedNote)"
  }
}
