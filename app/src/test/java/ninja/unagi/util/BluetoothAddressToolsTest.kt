package ninja.unagi.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BluetoothAddressToolsTest {
  @Test
  fun `normalizeAddress strips separators`() {
    assertEquals("001122AABBCC", BluetoothAddressTools.normalizeAddress("00:11:22:aa:bb:cc"))
  }

  @Test
  fun `normalizeFilterFragment accepts colon and dash mac input`() {
    assertEquals("001122", BluetoothAddressTools.normalizeFilterFragment("00:11:22"))
    assertEquals("AABBCC", BluetoothAddressTools.normalizeFilterFragment("aa-bb-cc"))
  }

  @Test
  fun `normalizeFilterFragment accepts digit heavy raw input`() {
    assertEquals("00112233", BluetoothAddressTools.normalizeFilterFragment("00112233"))
  }

  @Test
  fun `normalizeFilterFragment ignores plain text queries`() {
    assertNull(BluetoothAddressTools.normalizeFilterFragment("airtag"))
    assertNull(BluetoothAddressTools.normalizeFilterFragment("beacon"))
  }
}
