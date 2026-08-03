package ninja.unagi.alerts

import ninja.unagi.data.AlertRuleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
  fun `normalize company and service pair uses stable hex storage`() {
    val normalized = AlertRuleInputNormalizer.normalize(
      AlertRuleType.COMPANY_SERVICE,
      "0x1ab + 0xFD5F"
    )

    assertEquals("01ab+fd5f", normalized?.pattern)
    assertEquals("0x01AB + 0xFD5F", normalized?.displayValue)
  }

  @Test
  fun `company and service input requires exactly one bounded pair`() {
    listOf("01AB", "01AB FD5F", "01AB + FD5F + FEE0", "10000 + FD5F").forEach { input ->
      assertEquals(
        input,
        null,
        AlertRuleInputNormalizer.normalize(AlertRuleType.COMPANY_SERVICE, input)
      )
    }
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
  fun `name prefix normalization preserves significant trailing space`() {
    val normalized = AlertRuleInputNormalizer.normalize(AlertRuleType.NAME_PREFIX, "QT ")

    assertEquals("qt ", normalized?.pattern)
    assertEquals("QT ", normalized?.displayValue)
  }

  @Test
  fun `name prefix rule matches KARR signature across bluetooth name sources`() {
    val rule = karrRule()

    val observations = listOf(
      alertObservation(displayName = "QT 123456"),
      alertObservation(displayName = null, advertisedName = "qt ABC123"),
      alertObservation(displayName = null, systemName = "Qt serial-7")
    )

    observations.forEach { observation ->
      assertEquals(1, DeviceAlertMatcher.findMatches(listOf(rule), observation).size)
    }
  }

  @Test
  fun `name prefix rule rejects missing prefix delimiter position or suffix`() {
    val rule = karrRule()
    val nonMatches = listOf(
      "QT",
      "QT ",
      "QT123456",
      "QTimer 123",
      "XQT 123",
      "Device QT 123",
      " QT 123"
    )

    nonMatches.forEach { name ->
      assertFalse(
        name,
        DeviceAlertMatcher.findMatches(
          listOf(rule),
          alertObservation(displayName = name)
        ).isNotEmpty()
      )
    }
  }

  @Test
  fun `generic name prefix rule also matches an exact whole name`() {
    val rule = karrRule().copy(
      matchPattern = "beacon",
      displayValue = "Beacon"
    )

    assertEquals(
      1,
      DeviceAlertMatcher.findMatches(
        listOf(rule),
        alertObservation(displayName = "Beacon")
      ).size
    )
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

  @Test
  fun `company and service rule matches the observed Meta passive pair`() {
    val observation = alertObservation(
      displayName = null,
      manufacturerCompanyIds = setOf(0x01AB),
      serviceUuids = listOf("0000FD5F-0000-1000-8000-00805F9B34FB")
    )

    val matches = DeviceAlertMatcher.findMatches(listOf(metaPairRule()), observation)

    assertEquals(1, matches.size)
    assertEquals("Matched Company + service 0x01AB + 0xFD5F", matches.single().reason)
  }

  @Test
  fun `company and service rule ignores either Meta marker alone or wrong pairs`() {
    val nonMatches = listOf(
      alertObservation(displayName = null, manufacturerCompanyIds = setOf(0x01AB)),
      alertObservation(displayName = null, serviceUuids = listOf("FD5F")),
      alertObservation(
        displayName = null,
        manufacturerCompanyIds = setOf(0x0D53),
        serviceUuids = listOf("FD5F")
      ),
      alertObservation(
        displayName = null,
        manufacturerCompanyIds = setOf(0x01AB),
        serviceUuids = listOf("FEB7")
      ),
      alertObservation(
        displayName = null,
        manufacturerCompanyIds = setOf(0x01AB),
        serviceUuids = listOf("5FFD")
      )
    )

    nonMatches.forEach { observation ->
      assertTrue(DeviceAlertMatcher.findMatches(listOf(metaPairRule()), observation).isEmpty())
    }
  }

  @Test
  fun `malformed stored company and service rule fails closed`() {
    val malformed = metaPairRule().copy(matchPattern = "01ab/fd5f")
    val observation = alertObservation(
      displayName = null,
      manufacturerCompanyIds = setOf(0x01AB),
      serviceUuids = listOf("FD5F")
    )

    assertTrue(DeviceAlertMatcher.findMatches(listOf(malformed), observation).isEmpty())
  }

  @Test
  fun `company and service rule is limited to BLE observations`() {
    val classicObservation = alertObservation(
      displayName = null,
      manufacturerCompanyIds = setOf(0x01AB),
      serviceUuids = listOf("FD5F"),
      source = "Classic"
    )

    assertTrue(
      DeviceAlertMatcher.findMatches(listOf(metaPairRule()), classicObservation).isEmpty()
    )
  }

  @Test
  fun `Meta pair does not duplicate a matching legacy Ray Ban name alert`() {
    val rayBanNameRule = AlertRuleEntity(
      id = 5,
      matchType = AlertRuleType.NAME.storageValue,
      matchPattern = "ray-ban",
      displayValue = "Ray-Ban",
      emoji = "🕶️",
      soundPreset = AlertSoundPreset.CHIME.storageValue,
      enabled = true,
      createdAt = 5
    )
    val observation = alertObservation(
      displayName = "Ray-Ban Meta",
      manufacturerCompanyIds = setOf(0x01AB),
      serviceUuids = listOf("FD5F")
    )

    val matches = DeviceAlertMatcher.findMatches(
      listOf(rayBanNameRule, metaPairRule()),
      observation
    )

    assertEquals(listOf(rayBanNameRule.id), matches.map { it.rule.id })
  }

  private fun karrRule() = AlertRuleEntity(
    id = 3,
    matchType = AlertRuleType.NAME_PREFIX.storageValue,
    matchPattern = "qt ",
    displayValue = "QT ",
    emoji = "🚨",
    soundPreset = AlertSoundPreset.ALARM.storageValue,
    enabled = true,
    createdAt = 3
  )

  private fun metaPairRule() = AlertRuleEntity(
    id = 4,
    matchType = AlertRuleType.COMPANY_SERVICE.storageValue,
    matchPattern = "01ab+fd5f",
    displayValue = "0x01AB + 0xFD5F",
    emoji = "🕶️",
    soundPreset = AlertSoundPreset.CHIME.storageValue,
    enabled = true,
    createdAt = 4
  )

  private fun alertObservation(
    displayName: String?,
    advertisedName: String? = null,
    systemName: String? = null,
    manufacturerCompanyIds: Set<Int> = emptySet(),
    serviceUuids: List<String> = emptyList(),
    source: String = "BLE"
  ) = AlertObservation(
    deviceKey = "device-karr",
    displayName = displayName,
    advertisedName = advertisedName,
    systemName = systemName,
    address = "00:11:22:33:44:55",
    vendorName = null,
    source = source,
    manufacturerCompanyIds = manufacturerCompanyIds,
    serviceUuids = serviceUuids
  )
}
