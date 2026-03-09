package ninja.unagi.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TpmsObservationBuilderTest {
  @Test
  fun `builds observation with correct display name`() {
    val reading = sampleReading()
    val input = TpmsObservationBuilder.build(reading)
    assertEquals("TPMS PMV-107J 0x00ABCDEF", input.name)
  }

  @Test
  fun `sets SDR source and transport`() {
    val input = TpmsObservationBuilder.build(sampleReading())
    assertEquals("SDR", input.source)
    assertEquals("sdr", input.transport)
  }

  @Test
  fun `sets TPMS_SENSOR classification with high confidence`() {
    val input = TpmsObservationBuilder.build(sampleReading())
    assertEquals("tpms_sensor", input.classificationCategory)
    assertEquals("TPMS sensor", input.classificationLabel)
    assertEquals("high", input.classificationConfidence)
  }

  @Test
  fun `has no address`() {
    val input = TpmsObservationBuilder.build(sampleReading())
    assertNull(input.address)
    assertNull(input.normalizedAddress)
  }

  @Test
  fun `sets mapped vendor name for known protocol`() {
    val input = TpmsObservationBuilder.build(sampleReading())
    assertEquals("Pacific Industrial Co.", input.vendorName)
    assertEquals("rtl_433 protocol (mapped)", input.vendorSource)
    assertEquals("high", input.vendorConfidence)
  }

  @Test
  fun `falls back to model name for unknown protocol`() {
    val reading = sampleReading().copy(model = "SomeNewProtocol")
    val input = TpmsObservationBuilder.build(reading)
    assertEquals("SomeNewProtocol", input.vendorName)
    assertEquals("rtl_433 protocol", input.vendorSource)
    assertEquals("medium", input.vendorConfidence)
  }

  @Test
  fun `populates TPMS fields`() {
    val reading = sampleReading()
    val input = TpmsObservationBuilder.build(reading)
    assertEquals("PMV-107J", input.tpmsModel)
    assertEquals("0x00ABCDEF", input.tpmsSensorId)
    assertEquals(220.5, input.tpmsPressureKpa!!, 0.01)
    assertEquals(28.0, input.tpmsTemperatureC!!, 0.01)
    assertEquals(true, input.tpmsBatteryOk)
  }

  @Test
  fun `uses rssi from reading or defaults to -100`() {
    val withRssi = TpmsObservationBuilder.build(sampleReading(rssi = -45.0))
    assertEquals(-45, withRssi.rssi)

    val withoutRssi = TpmsObservationBuilder.build(sampleReading(rssi = null))
    assertEquals(-100, withoutRssi.rssi)
  }

  @Test
  fun `includes classification evidence`() {
    val input = TpmsObservationBuilder.build(sampleReading())
    assertTrue(input.classificationEvidence.any { it.contains("sdr") })
    assertTrue(input.classificationEvidence.any { it.contains("PMV-107J") })
  }

  private fun sampleReading(rssi: Double? = -12.3) = TpmsReading(
    model = "PMV-107J",
    sensorId = "0x00ABCDEF",
    pressureKpa = 220.5,
    temperatureC = 28.0,
    batteryOk = true,
    status = 0,
    rssi = rssi,
    snr = 15.2,
    frequencyMhz = 433.92,
    rawJson = "{}"
  )
}
