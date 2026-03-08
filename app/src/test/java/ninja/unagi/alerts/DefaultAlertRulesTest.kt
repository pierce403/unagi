package ninja.unagi.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAlertRulesTest {
  @Test
  fun `builds expected seeded default alerts`() {
    val rules = DefaultAlertRules.buildEntities(nowMs = 100L)

    assertEquals(5, rules.size)
    assertTrue(rules.any { it.matchPattern == "flipper" && it.soundPreset == AlertSoundPreset.CHIME.storageValue })
    assertTrue(rules.any { it.matchPattern == "axon body" && it.soundPreset == AlertSoundPreset.ALARM.storageValue })
    assertTrue(rules.any { it.matchPattern == "taser" && it.soundPreset == AlertSoundPreset.ALARM.storageValue })
    assertTrue(rules.any { it.matchPattern == "ray-ban" && it.emoji == "🕶️" })
    assertTrue(rules.any { it.matchPattern == "ray ban" && it.emoji == "🕶️" })
  }
}
