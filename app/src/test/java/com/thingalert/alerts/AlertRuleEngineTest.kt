package com.thingalert.alerts

import com.thingalert.data.AlertRuleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertRuleEngineTest {
  @Test
  fun `normalize OUI formats first three octets`() {
    val normalized = AlertRuleInputNormalizer.normalize(AlertRuleType.OUI, "00-11-22-33-44-55")

    assertEquals("001122", normalized?.pattern)
    assertEquals("00:11:22", normalized?.displayValue)
  }

  @Test
  fun `normalize MAC requires full address`() {
    val normalized = AlertRuleInputNormalizer.normalize(AlertRuleType.MAC, "00:11:22:33:44:55")

    assertEquals("001122334455", normalized?.pattern)
    assertEquals("00:11:22:33:44:55", normalized?.displayValue)
  }

  @Test
  fun `name rule matches advertised and system names`() {
    val rule = AlertRuleEntity(
      id = 1,
      matchType = AlertRuleType.NAME.storageValue,
      matchPattern = "airtag",
      displayValue = "AirTag",
      emoji = "👀",
      soundPreset = AlertSoundPreset.PING.storageValue,
      enabled = true,
      createdAt = 1
    )
    val observation = AlertObservation(
      deviceKey = "device-1",
      displayName = null,
      advertisedName = "AirTag 1",
      systemName = null,
      address = "00:11:22:33:44:55",
      vendorName = "Apple",
      source = "BLE"
    )

    val matches = DeviceAlertMatcher.findMatches(listOf(rule), observation)

    assertEquals(1, matches.size)
    assertEquals("Matched Name AirTag", matches.first().reason)
  }

  @Test
  fun `oui rule matches mac prefix`() {
    val rule = AlertRuleEntity(
      id = 2,
      matchType = AlertRuleType.OUI.storageValue,
      matchPattern = "001122",
      displayValue = "00:11:22",
      emoji = "🚨",
      soundPreset = AlertSoundPreset.ALARM.storageValue,
      enabled = true,
      createdAt = 2
    )
    val observation = AlertObservation(
      deviceKey = "device-2",
      displayName = "Beacon",
      advertisedName = "Beacon",
      systemName = "Beacon",
      address = "00:11:22:33:44:55",
      vendorName = "Acme",
      source = "BLE"
    )

    val matches = DeviceAlertMatcher.findMatches(listOf(rule), observation)

    assertTrue(matches.isNotEmpty())
    assertEquals("Matched OUI 00:11:22", matches.first().reason)
  }
}
