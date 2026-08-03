package ninja.unagi.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAlertRulesTest {
  @Test
  fun `builds expected seeded default alerts`() {
    val rules = DefaultAlertRules.buildEntities(nowMs = 100L)

    assertEquals(7, rules.size)
    assertTrue(rules.any { it.matchPattern == "flipper" && it.soundPreset == AlertSoundPreset.CHIME.storageValue })
    assertTrue(rules.any { it.matchPattern == "axon body" && it.soundPreset == AlertSoundPreset.ALARM.storageValue })
    assertTrue(rules.any { it.matchPattern == "taser" && it.soundPreset == AlertSoundPreset.ALARM.storageValue })
    assertTrue(rules.any { it.matchPattern == "ray-ban" && it.emoji == "🕶️" })
    assertTrue(rules.any { it.matchPattern == "ray ban" && it.emoji == "🕶️" })
    assertTrue(
      rules.any {
        it.matchType == AlertRuleType.COMPANY_SERVICE.storageValue &&
          it.matchPattern == "01ab+fd5f" &&
          it.emoji == "🕶️"
      }
    )
    assertTrue(
      rules.any {
        it.matchType == AlertRuleType.NAME_PREFIX.storageValue &&
          it.matchPattern == "qt " &&
          it.soundPreset == AlertSoundPreset.ALARM.storageValue
      }
    )
  }

  @Test
  fun `v2 seed contains only the KARR default`() {
    val rules = DefaultAlertRules.buildV2Entities(nowMs = 200L)

    assertEquals(1, rules.size)
    assertEquals(AlertRuleType.NAME_PREFIX.storageValue, rules.single().matchType)
    assertEquals("qt ", rules.single().matchPattern)
  }

  @Test
  fun `v3 seed contains only the Meta passive pair`() {
    val rules = DefaultAlertRules.buildV3Entities(nowMs = 250L)

    assertEquals(1, rules.size)
    assertEquals(AlertRuleType.COMPANY_SERVICE.storageValue, rules.single().matchType)
    assertEquals("01ab+fd5f", rules.single().matchPattern)
    assertEquals("0x01AB + 0xFD5F", rules.single().displayValue)
  }

  @Test
  fun `fresh install seeds legacy and KARR versions`() {
    val versions = DefaultAlertRules.seedVersions(
      v1Seeded = false,
      karrV2Seeded = false,
      metaPassiveV3Seeded = false
    )
    val rules = versions.flatMapIndexed { index, version ->
      DefaultAlertRules.buildEntities(version, nowMs = 300L + index)
    }

    assertEquals(
      listOf(
        DefaultAlertSeedVersion.V1,
        DefaultAlertSeedVersion.KARR_V2,
        DefaultAlertSeedVersion.META_PASSIVE_V3
      ),
      versions
    )
    assertEquals(7, rules.size)
  }

  @Test
  fun `v1 upgrade seeds KARR and Meta without restoring deleted legacy defaults`() {
    val versions = DefaultAlertRules.seedVersions(
      v1Seeded = true,
      karrV2Seeded = false,
      metaPassiveV3Seeded = false
    )
    val candidates = versions.flatMap { version ->
      DefaultAlertRules.buildEntities(version, nowMs = 400L)
    }

    assertEquals(
      listOf(DefaultAlertSeedVersion.KARR_V2, DefaultAlertSeedVersion.META_PASSIVE_V3),
      versions
    )
    assertEquals(2, candidates.size)
    assertTrue(candidates.any { it.matchPattern == "qt " })
    assertTrue(candidates.any { it.matchPattern == "01ab+fd5f" })
    assertFalse(candidates.any { it.matchPattern == "flipper" })
  }

  @Test
  fun `v2 upgrade seeds only the Meta passive pair`() {
    val versions = DefaultAlertRules.seedVersions(
      v1Seeded = true,
      karrV2Seeded = true,
      metaPassiveV3Seeded = false
    )
    val candidates = versions.flatMap { version ->
      DefaultAlertRules.buildEntities(version, nowMs = 450L)
    }

    assertEquals(listOf(DefaultAlertSeedVersion.META_PASSIVE_V3), versions)
    assertEquals(listOf("01ab+fd5f"), candidates.map { it.matchPattern })
  }

  @Test
  fun `seed decisions preserve existing and post-seed deleted KARR rules`() {
    val existingKarr = DefaultAlertRules.buildV2Entities(nowMs = 500L)
      .single()
      .copy(enabled = false)
    val missing = DefaultAlertRules.missingEntities(
      existingRules = listOf(existingKarr),
      candidates = DefaultAlertRules.buildV2Entities(nowMs = 600L)
    )
    val versionsAfterSeed = DefaultAlertRules.seedVersions(
      v1Seeded = true,
      karrV2Seeded = true,
      metaPassiveV3Seeded = true
    )

    assertTrue(missing.isEmpty())
    assertTrue(versionsAfterSeed.isEmpty())
  }
}
