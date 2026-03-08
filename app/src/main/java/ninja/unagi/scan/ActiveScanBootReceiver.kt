package ninja.unagi.scan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ninja.unagi.util.DebugLog

class ContinuousScanBootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
      return
    }
    if (!ContinuousScanPreferences.isEnabled(context) || !StartOnBootPreferences.isEnabled(context)) {
      DebugLog.log("Boot completed received; continuous scan autostart disabled")
      return
    }
    DebugLog.log("Boot completed received; starting continuous scan service")
    ContinuousScanService.start(context)
  }
}
