package ninja.unagi.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TpmsSensorVendorTest {
  @Test
  fun `lookup returns known vendor for Schrader`() {
    val vendor = TpmsSensorVendorLookup.lookup("Schrader")
    assertNotNull(vendor)
    assertEquals("Sensata Technologies (Schrader)", vendor!!.manufacturer)
    assertEquals("Schrader", vendor.protocol)
  }

  @Test
  fun `lookup returns known vendor for PMV-107J`() {
    val vendor = TpmsSensorVendorLookup.lookup("PMV-107J")
    assertNotNull(vendor)
    assertEquals("Pacific Industrial Co.", vendor!!.manufacturer)
  }

  @Test
  fun `lookup is case-insensitive`() {
    val upper = TpmsSensorVendorLookup.lookup("SCHRADER")
    val lower = TpmsSensorVendorLookup.lookup("schrader")
    val mixed = TpmsSensorVendorLookup.lookup("Schrader")
    assertNotNull(upper)
    assertNotNull(lower)
    assertNotNull(mixed)
    assertEquals(upper!!.manufacturer, lower!!.manufacturer)
    assertEquals(lower.manufacturer, mixed!!.manufacturer)
  }

  @Test
  fun `lookup returns null for unknown model`() {
    assertNull(TpmsSensorVendorLookup.lookup("SomeNewProtocol"))
    assertNull(TpmsSensorVendorLookup.lookup(""))
  }

  @Test
  fun `all known vendors have non-blank manufacturer`() {
    val knownModels = listOf(
      "Schrader", "PMV-107J", "Toyota", "Hyundai-VDO", "Ford",
      "Renault", "Jansite", "Jansite-Solar", "Abarth-124Spider",
      "Citroen", "Peugeot"
    )
    for (model in knownModels) {
      val vendor = TpmsSensorVendorLookup.lookup(model)
      assertNotNull("Missing vendor for $model", vendor)
      assert(vendor!!.manufacturer.isNotBlank()) { "Blank manufacturer for $model" }
    }
  }
}
