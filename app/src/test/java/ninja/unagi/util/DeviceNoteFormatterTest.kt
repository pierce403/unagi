package ninja.unagi.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceNoteFormatterTest {
  @Test
  fun `normalize trims blanks and caps note length`() {
    assertEquals("12345678901234567890", DeviceNoteFormatter.normalize(" 12345678901234567890extra "))
    assertNull(DeviceNoteFormatter.normalize("   "))
  }

  @Test
  fun `normalize preserves apostrophes and flattens control whitespace`() {
    assertEquals("DEAN'S FLIPPER", DeviceNoteFormatter.normalize("DEAN'S FLIPPER"))
    assertEquals("Dean's Flipper", DeviceNoteFormatter.normalize(" Dean's\nFlipper\t"))
  }

  @Test
  fun `appendToTitle adds note in parentheses when present`() {
    assertEquals("Pixel 8 (Dean's Phone)", DeviceNoteFormatter.appendToTitle("Pixel 8", "Dean's Phone"))
    assertEquals("Pixel 8", DeviceNoteFormatter.appendToTitle("Pixel 8", " "))
  }
}
