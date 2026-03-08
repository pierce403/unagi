package ninja.unagi.scan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ninja.unagi.util.DebugLog

class ActiveScanBootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
      return
    }
    if (!ActiveScanPreferences.isEnabled(context) || !StartOnBootPreferences.isEnabled(context)) {
      DebugLog.log("Boot completed received; active scan autostart disabled")
      return
    }
    DebugLog.log("Boot completed received; starting active scan service")
    ActiveScanService.start(context)
  }
}
