package ninja.unagi.alerts

import android.content.Context
import androidx.core.content.edit
import ninja.unagi.data.AlertRuleEntity
import ninja.unagi.data.AlertRuleRepository
import ninja.unagi.util.BluetoothNameSignatures

data class DefaultAlertRule(
  val type: AlertRuleType,
  val rawInput: String,
  val emoji: String,
  val soundPreset: AlertSoundPreset
)

internal enum class DefaultAlertSeedVersion {
  V1,
  KARR_V2
}

object DefaultAlertRules {
  private val v1Rules = listOf(
    DefaultAlertRule(
      type = AlertRuleType.NAME,
      rawInput = "Flipper",
      emoji = "📡",
      soundPreset = AlertSoundPreset.CHIME
    ),
    DefaultAlertRule(
      type = AlertRuleType.NAME,
      rawInput = "Axon Body",
      emoji = "🚨",
      soundPreset = AlertSoundPreset.ALARM
    ),
    DefaultAlertRule(
      type = AlertRuleType.NAME,
      rawInput = "TASER",
      emoji = "🚨",
      soundPreset = AlertSoundPreset.ALARM
    ),
    DefaultAlertRule(
      type = AlertRuleType.NAME,
      rawInput = "Ray-Ban",
      emoji = "🕶️",
      soundPreset = AlertSoundPreset.CHIME
    ),
    DefaultAlertRule(
      type = AlertRuleType.NAME,
      rawInput = "Ray Ban",
      emoji = "🕶️",
      soundPreset = AlertSoundPreset.CHIME
    )
  )

  private val v2Rules = listOf(
    DefaultAlertRule(
      type = AlertRuleType.NAME_PREFIX,
      rawInput = BluetoothNameSignatures.KARR_NAME_PREFIX,
      emoji = "🚨",
      soundPreset = AlertSoundPreset.ALARM
    )
  )

  fun buildEntities(nowMs: Long = System.currentTimeMillis()): List<AlertRuleEntity> {
    return buildEntities(v1Rules + v2Rules, nowMs)
  }

  internal fun buildV1Entities(nowMs: Long = System.currentTimeMillis()): List<AlertRuleEntity> {
    return buildEntities(DefaultAlertSeedVersion.V1, nowMs)
  }

  internal fun buildV2Entities(nowMs: Long = System.currentTimeMillis()): List<AlertRuleEntity> {
    return buildEntities(DefaultAlertSeedVersion.KARR_V2, nowMs)
  }

  internal fun seedVersions(
    v1Seeded: Boolean,
    karrV2Seeded: Boolean
  ): List<DefaultAlertSeedVersion> {
    return buildList {
      if (!v1Seeded) add(DefaultAlertSeedVersion.V1)
      if (!karrV2Seeded) add(DefaultAlertSeedVersion.KARR_V2)
    }
  }

  internal fun buildEntities(
    version: DefaultAlertSeedVersion,
    nowMs: Long = System.currentTimeMillis()
  ): List<AlertRuleEntity> {
    val rules = when (version) {
      DefaultAlertSeedVersion.V1 -> v1Rules
      DefaultAlertSeedVersion.KARR_V2 -> v2Rules
    }
    return buildEntities(rules, nowMs)
  }

  internal fun missingEntities(
    existingRules: List<AlertRuleEntity>,
    candidates: List<AlertRuleEntity>
  ): List<AlertRuleEntity> {
    val existingRuleKeys = existingRules
      .map { it.matchType to it.matchPattern }
      .toSet()
    return candidates
      .distinctBy { it.matchType to it.matchPattern }
      .filterNot { (it.matchType to it.matchPattern) in existingRuleKeys }
  }

  private fun buildEntities(
    rules: List<DefaultAlertRule>,
    nowMs: Long
  ): List<AlertRuleEntity> {
    return rules.mapIndexedNotNull { index, rule ->
      val normalized = AlertRuleInputNormalizer.normalize(rule.type, rule.rawInput) ?: return@mapIndexedNotNull null
      AlertRuleEntity(
        matchType = rule.type.storageValue,
        matchPattern = normalized.pattern,
        displayValue = normalized.displayValue,
        emoji = rule.emoji,
        soundPreset = rule.soundPreset.storageValue,
        enabled = true,
        createdAt = nowMs + index
      )
    }
  }
}

object DefaultAlertSeeder {
  private const val PREFS_NAME = "unagi_alert_defaults"
  private const val KEY_DEFAULTS_SEEDED_V1 = "defaults_seeded_v1"
  private const val KEY_KARR_DEFAULT_SEEDED_V2 = "karr_default_seeded_v2"

  suspend fun seedIfNeeded(
    context: Context,
    repository: AlertRuleRepository
  ) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val versions = DefaultAlertRules.seedVersions(
      v1Seeded = prefs.getBoolean(KEY_DEFAULTS_SEEDED_V1, false),
      karrV2Seeded = prefs.getBoolean(KEY_KARR_DEFAULT_SEEDED_V2, false)
    )

    versions.forEach { version ->
      val candidates = DefaultAlertRules.buildEntities(version)
      DefaultAlertRules.missingEntities(repository.getRules(), candidates).forEach { rule ->
        repository.addRule(rule)
      }
      prefs.edit {
        putBoolean(preferenceKey(version), true)
      }
    }
  }

  private fun preferenceKey(version: DefaultAlertSeedVersion): String {
    return when (version) {
      DefaultAlertSeedVersion.V1 -> KEY_DEFAULTS_SEEDED_V1
      DefaultAlertSeedVersion.KARR_V2 -> KEY_KARR_DEFAULT_SEEDED_V2
    }
  }
}
