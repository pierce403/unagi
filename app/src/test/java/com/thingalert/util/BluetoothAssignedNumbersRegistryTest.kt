package com.thingalert.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BluetoothAssignedNumbersRegistryTest {
  private val registry = BluetoothAssignedNumbersRegistry.fromLines(
    companyLines = sequenceOf("004C|Apple", "0075|Samsung Electronics"),
    serviceLines = sequenceOf(
      "180F|Battery Service",
      "FEAA|Eddystone"
    )
  )

  @Test
  fun `resolves company identifiers`() {
    assertEquals("Apple", registry.companyName(0x004C))
    assertEquals("0x004C", registry.companyCode(0x004C))
    assertNull(registry.companyName(0x9999))
  }

  @Test
  fun `normalizes bluetooth base uuid services`() {
    assertEquals(
      "Battery Service",
      registry.serviceName("0000180F-0000-1000-8000-00805F9B34FB")
    )
    assertEquals(
      "0x180F",
      registry.serviceCode("0000180F-0000-1000-8000-00805F9B34FB")
    )
  }

  @Test
  fun `resolves member uuid services`() {
    assertEquals("Eddystone", registry.serviceName("FEAA"))
    assertEquals("0xFEAA", registry.serviceCode("FEAA"))
  }
}
