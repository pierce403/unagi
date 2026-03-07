package com.thingalert.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorPrefixRegistryTest {
  private val registry = VendorPrefixRegistry.fromLines(
    sequenceOf(
      "A1B2C3|Vendor A",
      "A1B2C3D|Vendor B",
      "A1B2C3D4E|Vendor C"
    )
  )

  @Test
  fun `resolve uses longest matching prefix`() {
    val resolution = registry.resolve("A1:B2:C3:D4:E5:F6")

    assertEquals("Vendor C", resolution?.vendorName)
    assertEquals("IEEE MA-S", resolution?.vendorSource)
  }

  @Test
  fun `resolve skips locally administered addresses`() {
    val resolution = registry.resolve("A2:B2:C3:D4:E5:F6")

    assertTrue(resolution?.locallyAdministered == true)
    assertNull(resolution?.vendorName)
  }

  @Test
  fun `normalizeAddress strips separators`() {
    assertEquals("A1B2C3D4E5F6", VendorPrefixRegistry.normalizeAddress("a1-b2-c3-d4-e5-f6"))
    assertNull(VendorPrefixRegistry.normalizeAddress("not-an-address"))
  }

  @Test
  fun `isLocallyAdministered checks first-octet local bit`() {
    assertTrue(VendorPrefixRegistry.isLocallyAdministered("A2B2C3D4E5F6"))
    assertFalse(VendorPrefixRegistry.isLocallyAdministered("A0B2C3D4E5F6"))
  }
}
