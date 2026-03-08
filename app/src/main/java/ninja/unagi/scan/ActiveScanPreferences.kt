package ninja.unagi.scan

import android.content.Context

object ActiveScanPreferences {
  private const val PREFS_NAME = "unagi_scan"
  private const val KEY_ACTIVE_SCANNING_ENABLED = "active_scanning_enabled"

  fun isEnabled(context: Context): Boolean {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .getBoolean(KEY_ACTIVE_SCANNING_ENABLED, false)
  }

  fun setEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putBoolean(KEY_ACTIVE_SCANNING_ENABLED, enabled)
      .apply()
  }
}
