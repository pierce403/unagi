package ninja.unagi.data

object DeviceMaintenancePolicy {
  const val PRUNE_INTERVAL_MS = 6 * 60 * 60 * 1000L

  fun shouldPrune(lastPrunedAt: Long, now: Long): Boolean {
    return lastPrunedAt == 0L || now - lastPrunedAt >= PRUNE_INTERVAL_MS
  }
}
