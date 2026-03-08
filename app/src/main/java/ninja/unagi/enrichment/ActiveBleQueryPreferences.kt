package ninja.unagi.enrichment

import android.content.Context

object ActiveBleQueryPreferences {
  private const val PREFS_NAME = "unagi_enrichment"
  private const val KEY_ACTIVE_BLE_QUERIES_ENABLED = "active_ble_queries_enabled"

  fun isEnabled(context: Context): Boolean {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .getBoolean(KEY_ACTIVE_BLE_QUERIES_ENABLED, false)
  }

  fun setEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putBoolean(KEY_ACTIVE_BLE_QUERIES_ENABLED, enabled)
      .apply()
  }
}
