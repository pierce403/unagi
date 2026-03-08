package ninja.unagi.data

object ContinuousSightingPolicy {
  const val MIN_NEW_SIGHTING_GAP_MS = 60_000L

  fun isNewSighting(lastSightingAt: Long?, observationTimestamp: Long): Boolean {
    return lastSightingAt == null ||
      observationTimestamp - lastSightingAt >= MIN_NEW_SIGHTING_GAP_MS
  }
}
