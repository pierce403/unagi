package com.thingalert.alerts

import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import com.thingalert.data.AlertRuleEntity
import com.thingalert.util.BluetoothAddressTools
import com.thingalert.util.Formatters

enum class AlertRuleType(
  val storageValue: String,
  val label: String,
  val inputHint: String
) {
  OUI("oui", "OUI", "OUI prefix, like 00:11:22"),
  MAC("mac", "MAC", "Full MAC, like 00:11:22:33:44:55"),
  NAME("name", "Name", "Bluetooth name, like AirTag");

  companion object {
    fun fromStorageValue(value: String?): AlertRuleType? {
      return entries.firstOrNull { it.storageValue == value }
    }
  }
}

enum class AlertEmojiPreset(
  val emoji: String,
  val label: String
) {
  EYES("👀", "Eyes"),
  BELL("🔔", "Bell"),
  WARNING("⚠️", "Warning"),
  RADAR("📡", "Radar"),
  SIREN("🚨", "Siren"),
  NINJA("🥷", "Ninja");

  companion object {
    fun fromEmoji(value: String?): AlertEmojiPreset? {
      return entries.firstOrNull { it.emoji == value }
    }
  }
}

enum class AlertSoundPreset(
  val storageValue: String,
  val label: String,
  val stopAfterMs: Long
) {
  PING("ping", "Ping", 900),
  CHIME("chime", "Chime", 1500),
  ALARM("alarm", "Alarm", 2400);

  fun resolveUri(): Uri {
    val primary = when (this) {
      PING -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
      CHIME -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
      ALARM -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
    }
    return primary ?: Settings.System.DEFAULT_NOTIFICATION_URI
  }

  companion object {
    fun fromStorageValue(value: String?): AlertSoundPreset? {
      return entries.firstOrNull { it.storageValue == value }
    }
  }
}

data class AlertRuleDraft(
  val type: AlertRuleType,
  val input: String,
  val emoji: AlertEmojiPreset,
  val soundPreset: AlertSoundPreset
)

data class NormalizedAlertRuleInput(
  val pattern: String,
  val displayValue: String
)

data class AlertObservation(
  val deviceKey: String,
  val displayName: String?,
  val advertisedName: String?,
  val systemName: String?,
  val address: String?,
  val vendorName: String?,
  val source: String
) {
  val normalizedAddress: String? = BluetoothAddressTools.normalizeAddress(address)
  val formattedAddress: String? = BluetoothAddressTools.formatAddress(normalizedAddress ?: address)
}

data class AlertMatch(
  val rule: AlertRuleEntity,
  val reason: String
)

object AlertRuleInputNormalizer {
  fun normalize(type: AlertRuleType, rawInput: String): NormalizedAlertRuleInput? {
    val trimmed = rawInput.trim()
    if (trimmed.isEmpty()) {
      return null
    }

    return when (type) {
      AlertRuleType.OUI -> {
        val normalized = BluetoothAddressTools.normalizeFilterFragment(trimmed)
          ?.takeIf { it.length >= 6 }
          ?.take(6)
          ?: return null
        val displayValue = BluetoothAddressTools.formatAddress(normalized) ?: normalized
        NormalizedAlertRuleInput(pattern = normalized, displayValue = displayValue)
      }
      AlertRuleType.MAC -> {
        val normalized = BluetoothAddressTools.normalizeAddress(trimmed)
          ?.takeIf { it.length == 12 }
          ?: return null
        val displayValue = BluetoothAddressTools.formatAddress(normalized) ?: normalized
        NormalizedAlertRuleInput(pattern = normalized, displayValue = displayValue)
      }
      AlertRuleType.NAME -> {
        NormalizedAlertRuleInput(pattern = trimmed.lowercase(), displayValue = trimmed)
      }
    }
  }
}

object DeviceAlertMatcher {
  fun findMatches(
    rules: List<AlertRuleEntity>,
    observation: AlertObservation
  ): List<AlertMatch> {
    if (rules.isEmpty()) {
      return emptyList()
    }

    return rules.filter { it.enabled }.mapNotNull { rule ->
      val type = AlertRuleType.fromStorageValue(rule.matchType) ?: return@mapNotNull null
      val matched = when (type) {
        AlertRuleType.OUI -> observation.normalizedAddress?.startsWith(rule.matchPattern) == true
        AlertRuleType.MAC -> observation.normalizedAddress == rule.matchPattern
        AlertRuleType.NAME -> {
          val candidates = listOfNotNull(
            observation.displayName,
            observation.advertisedName,
            observation.systemName
          ).map { it.lowercase() }
          candidates.any { it.contains(rule.matchPattern) }
        }
      }

      if (!matched) {
        null
      } else {
        AlertMatch(rule = rule, reason = "Matched ${type.label} ${rule.displayValue}")
      }
    }
  }
}

fun AlertRuleEntity.displayTitle(): String {
  val typeLabel = AlertRuleType.fromStorageValue(matchType)?.label ?: matchType
  return "$emoji $typeLabel ${displayValue}"
}

fun AlertRuleEntity.displayMeta(): String {
  val soundLabel = AlertSoundPreset.fromStorageValue(soundPreset)?.label ?: soundPreset
  val stateLabel = if (enabled) "Enabled" else "Disabled"
  return "Sound: $soundLabel • $stateLabel"
}

fun AlertObservation.displayTitle(): String {
  return Formatters.formatName(displayName, vendorName)
}
