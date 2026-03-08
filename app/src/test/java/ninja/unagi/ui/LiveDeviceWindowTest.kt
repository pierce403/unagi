package ninja.unagi.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDeviceWindowTest {
  @Test
  fun isLive_returnsTrueInsideWindow() {
    assertTrue(LiveDeviceWindow.isLive(lastSeen = 70_001L, now = 100_000L))
  }

  @Test
  fun isLive_returnsTrueAtWindowBoundary() {
    assertTrue(LiveDeviceWindow.isLive(lastSeen = 70_000L, now = 100_000L))
  }

  @Test
  fun isLive_returnsFalseOutsideWindow() {
    assertFalse(LiveDeviceWindow.isLive(lastSeen = 69_999L, now = 100_000L))
  }
}
