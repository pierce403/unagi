package ninja.unagi.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceMaintenancePolicyTest {
  @Test
  fun pruneRunsWhenNothingHasBeenPrunedYet() {
    assertTrue(DeviceMaintenancePolicy.shouldPrune(lastPrunedAt = 0L, now = 1_000L))
  }

  @Test
  fun pruneSkipsUntilIntervalElapses() {
    assertFalse(
      DeviceMaintenancePolicy.shouldPrune(
        lastPrunedAt = 10_000L,
        now = 10_000L + DeviceMaintenancePolicy.PRUNE_INTERVAL_MS - 1L
      )
    )
  }

  @Test
  fun pruneRunsAgainOnceIntervalElapses() {
    assertTrue(
      DeviceMaintenancePolicy.shouldPrune(
        lastPrunedAt = 10_000L,
        now = 10_000L + DeviceMaintenancePolicy.PRUNE_INTERVAL_MS
      )
    )
  }
}
