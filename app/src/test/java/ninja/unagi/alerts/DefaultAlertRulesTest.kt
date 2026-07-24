package ninja.unagi.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAlertRulesTest {
  @Test
  fun `builds expected seeded default alerts`() {
    val rules = DefaultAlertRules.buildEntities(nowMs = 100L)

    assertEquals(6, rules.size)
    assertTrue(rules.any { it.matchPattern == "flipper" && it.soundPreset == AlertSoundPreset.CHIME.storageValue })
    assertTrue(rules.any { it.matchPattern == "axon body" && it.soundPreset == AlertSoundPreset.ALARM.storageValue })
    assertTrue(rules.any { it.matchPattern == "taser" && it.soundPreset == AlertSoundPreset.ALARM.storageValue })
    assertTrue(rules.any { it.matchPattern == "ray-ban" && it.emoji == "🕶️" })
    assertTrue(rules.any { it.matchPattern == "ray ban" && it.emoji == "🕶️" })
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
  fun `fresh install seeds legacy and KARR versions`() {
    val versions = DefaultAlertRules.seedVersions(
      v1Seeded = false,
      karrV2Seeded = false
    )
    val rules = versions.flatMapIndexed { index, version ->
      DefaultAlertRules.buildEntities(version, nowMs = 300L + index)
    }

    assertEquals(
      listOf(DefaultAlertSeedVersion.V1, DefaultAlertSeedVersion.KARR_V2),
      versions
    )
    assertEquals(6, rules.size)
  }

  @Test
  fun `v1 upgrade seeds only KARR without restoring deleted legacy defaults`() {
    val versions = DefaultAlertRules.seedVersions(
      v1Seeded = true,
      karrV2Seeded = false
    )
    val candidates = versions.flatMap { version ->
      DefaultAlertRules.buildEntities(version, nowMs = 400L)
    }

    assertEquals(listOf(DefaultAlertSeedVersion.KARR_V2), versions)
    assertEquals(1, candidates.size)
    assertEquals("qt ", candidates.single().matchPattern)
    assertFalse(candidates.any { it.matchPattern == "flipper" })
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
      karrV2Seeded = true
    )

    assertTrue(missing.isEmpty())
    assertTrue(versionsAfterSeed.isEmpty())
  }
}
