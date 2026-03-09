package ninja.unagi.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Rtl433JsonParserTest {
  @Test
  fun `parses complete TPMS JSON line`() {
    val json = """
      {
        "time": "2024-01-15 10:30:00",
        "model": "Toyota",
        "type": "TPMS",
        "id": "0x00ABCDEF",
        "status": 0,
        "battery_ok": 1,
        "pressure_kPa": 220.5,
        "temperature_C": 28.0,
        "rssi": -12.3,
        "snr": 15.2,
        "freq": 433.92
      }
    """.trimIndent()

    val reading = Rtl433JsonParser.parse(json)
    assertNotNull(reading)
    reading!!
    assertEquals("Toyota", reading.model)
    assertEquals("0x00ABCDEF", reading.sensorId)
    assertEquals(220.5, reading.pressureKpa!!, 0.01)
    assertEquals(28.0, reading.temperatureC!!, 0.01)
    assertEquals(true, reading.batteryOk)
    assertEquals(0, reading.status)
    assertEquals(-12.3, reading.rssi!!, 0.01)
    assertEquals(15.2, reading.snr!!, 0.01)
    assertEquals(433.92, reading.frequencyMhz!!, 0.01)
  }

  @Test
  fun `parses TPMS with battery_ok zero as false`() {
    val json = """{"type":"TPMS","model":"Schrader","id":"0x12345678","battery_ok":0,"pressure_kPa":180.0}"""
    val reading = Rtl433JsonParser.parse(json)
    assertNotNull(reading)
    assertEquals(false, reading!!.batteryOk)
  }

  @Test
  fun `returns null for non-TPMS type`() {
    val json = """{"type":"weather","model":"Acurite","id":"12345","temperature_C":22.5}"""
    assertNull(Rtl433JsonParser.parse(json))
  }

  @Test
  fun `returns null for missing type field`() {
    val json = """{"model":"Toyota","id":"0x00ABCDEF","pressure_kPa":220.5}"""
    assertNull(Rtl433JsonParser.parse(json))
  }

  @Test
  fun `returns null for invalid JSON`() {
    assertNull(Rtl433JsonParser.parse("not json"))
    assertNull(Rtl433JsonParser.parse(""))
    assertNull(Rtl433JsonParser.parse("{"))
  }

  @Test
  fun `handles missing optional fields`() {
    val json = """{"type":"TPMS","model":"Unknown","id":"0x00000000"}"""
    val reading = Rtl433JsonParser.parse(json)
    assertNotNull(reading)
    reading!!
    assertEquals("Unknown", reading.model)
    assertEquals("0x00000000", reading.sensorId)
    assertNull(reading.pressureKpa)
    assertNull(reading.temperatureC)
    assertNull(reading.batteryOk)
    assertNull(reading.rssi)
    assertNull(reading.snr)
    assertNull(reading.frequencyMhz)
  }

  @Test
  fun `preserves raw JSON in reading`() {
    val json = """{"type":"TPMS","model":"Ford","id":"0xAABBCCDD"}"""
    val reading = Rtl433JsonParser.parse(json)
    assertNotNull(reading)
    assertEquals(json, reading!!.rawJson)
  }

  @Test
  fun `handles case-insensitive TPMS type`() {
    val json = """{"type":"tpms","model":"Toyota","id":"0x11223344"}"""
    val reading = Rtl433JsonParser.parse(json)
    assertNotNull(reading)
    assertEquals("Toyota", reading!!.model)
  }

  @Test
  fun `parses pressure_PSI and converts to kPa`() {
    val json = """{"type":"TPMS","model":"Schrader","id":"0x12345678","pressure_PSI":32.0}"""
    val reading = Rtl433JsonParser.parse(json)
    assertNotNull(reading)
    assertEquals(220.6, reading!!.pressureKpa!!, 0.5)
  }

  @Test
  fun `parses pressure_bar and converts to kPa`() {
    val json = """{"type":"TPMS","model":"Renault","id":"0xDEADBEEF","pressure_bar":2.2}"""
    val reading = Rtl433JsonParser.parse(json)
    assertNotNull(reading)
    assertEquals(220.0, reading!!.pressureKpa!!, 0.1)
  }

  @Test
  fun `prefers pressure_kPa over PSI or bar when multiple present`() {
    val json = """{"type":"TPMS","model":"Ford","id":"0xAABBCCDD","pressure_kPa":215.0,"pressure_PSI":32.0,"pressure_bar":2.2}"""
    val reading = Rtl433JsonParser.parse(json)
    assertNotNull(reading)
    assertEquals(215.0, reading!!.pressureKpa!!, 0.01)
  }
}
