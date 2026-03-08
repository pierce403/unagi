package ninja.unagi.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousSightingPolicyTest {
  @Test
  fun `first observation always counts as a sighting`() {
    assertTrue(ContinuousSightingPolicy.isNewSighting(null, 1_000L))
  }

  @Test
  fun `continuous observations inside the gap do not count twice`() {
    assertFalse(
      ContinuousSightingPolicy.isNewSighting(
        lastSightingAt = 10_000L,
        observationTimestamp = 10_000L + ContinuousSightingPolicy.MIN_NEW_SIGHTING_GAP_MS - 1L
      )
    )
  }

  @Test
  fun `observations after the gap count as a new sighting`() {
    assertTrue(
      ContinuousSightingPolicy.isNewSighting(
        lastSightingAt = 10_000L,
        observationTimestamp = 10_000L + ContinuousSightingPolicy.MIN_NEW_SIGHTING_GAP_MS
      )
    )
  }
}
