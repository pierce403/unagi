package ninja.unagi.ui

import android.content.Context

object MainDisplayPreferences {
  private const val PREFS_NAME = "unagi_display"
  private const val KEY_TOP_BANNER_COLLAPSED = "top_banner_collapsed"
  private const val KEY_COMPACT_DEVICE_CARDS = "compact_device_cards"

  fun isTopBannerCollapsed(context: Context): Boolean {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .getBoolean(KEY_TOP_BANNER_COLLAPSED, false)
  }

  fun setTopBannerCollapsed(context: Context, collapsed: Boolean) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putBoolean(KEY_TOP_BANNER_COLLAPSED, collapsed)
      .apply()
  }

  fun isCompactDeviceCards(context: Context): Boolean {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .getBoolean(KEY_COMPACT_DEVICE_CARDS, false)
  }

  fun setCompactDeviceCards(context: Context, compact: Boolean) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putBoolean(KEY_COMPACT_DEVICE_CARDS, compact)
      .apply()
  }
}
