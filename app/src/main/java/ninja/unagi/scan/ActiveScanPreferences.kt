package ninja.unagi.scan

import android.content.Context

object ContinuousScanPreferences {
  private const val PREFS_NAME = "unagi_scan"
  private const val KEY_CONTINUOUS_SCANNING_ENABLED = "continuous_scanning_enabled"
  private const val LEGACY_KEY_ACTIVE_SCANNING_ENABLED = "active_scanning_enabled"

  fun isEnabled(context: Context): Boolean {
    val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return if (preferences.contains(KEY_CONTINUOUS_SCANNING_ENABLED)) {
      preferences.getBoolean(KEY_CONTINUOUS_SCANNING_ENABLED, false)
    } else {
      preferences.getBoolean(LEGACY_KEY_ACTIVE_SCANNING_ENABLED, false)
    }
  }

  fun setEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putBoolean(KEY_CONTINUOUS_SCANNING_ENABLED, enabled)
      .remove(LEGACY_KEY_ACTIVE_SCANNING_ENABLED)
      .apply()
  }
}
