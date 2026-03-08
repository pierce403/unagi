package ninja.unagi.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

object BatteryOptimizationHelper {
  fun canRequestExemption(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
  }

  fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    if (!canRequestExemption()) {
      return true
    }
    val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
  }

  fun requestIntent(context: Context): Intent? {
    if (!canRequestExemption()) {
      return null
    }
    return Intent(
      Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
      Uri.parse("package:${context.packageName}")
    )
  }
}
