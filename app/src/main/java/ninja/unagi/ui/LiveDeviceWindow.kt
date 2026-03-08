package ninja.unagi.ui

object LiveDeviceWindow {
  const val WINDOW_MS = 30_000L
  const val TICK_MS = 5_000L

  fun isLive(lastSeen: Long, now: Long): Boolean {
    return now - lastSeen <= WINDOW_MS
  }
}
