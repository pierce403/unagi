package ninja.unagi.scan

import android.content.Context

object StartOnBootPreferences {
  private const val PREFS_NAME = "unagi_scan"
  private const val KEY_START_ON_BOOT_ENABLED = "start_on_boot_enabled"

  fun isEnabled(context: Context): Boolean {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .getBoolean(KEY_START_ON_BOOT_ENABLED, false)
  }

  fun setEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putBoolean(KEY_START_ON_BOOT_ENABLED, enabled)
      .apply()
  }
}
