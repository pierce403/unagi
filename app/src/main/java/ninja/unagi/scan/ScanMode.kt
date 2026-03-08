package ninja.unagi.scan

import android.bluetooth.le.ScanSettings
import android.content.Context

enum class ScanModePreset(
  val storageValue: String,
  val label: String,
  val bleScanMode: Int,
  val startsClassicDiscovery: Boolean
) {
  NORMAL(
    storageValue = "normal",
    label = "Normal",
    bleScanMode = ScanSettings.SCAN_MODE_LOW_LATENCY,
    startsClassicDiscovery = true
  ),
  COMPATIBILITY(
    storageValue = "compatibility",
    label = "Compatibility",
    bleScanMode = ScanSettings.SCAN_MODE_BALANCED,
    startsClassicDiscovery = false
  );

  companion object {
    fun fromStorageValue(value: String?): ScanModePreset {
      return entries.firstOrNull { it.storageValue == value } ?: NORMAL
    }
  }
}

object ScanModePreferences {
  private const val PREFS_NAME = "unagi_scan"
  private const val KEY_SCAN_MODE = "scan_mode"

  fun get(context: Context): ScanModePreset {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return ScanModePreset.fromStorageValue(prefs.getString(KEY_SCAN_MODE, null))
  }

  fun set(context: Context, mode: ScanModePreset) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putString(KEY_SCAN_MODE, mode.storageValue)
      .apply()
  }
}
