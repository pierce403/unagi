package ninja.unagi.alerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ninja.unagi.R
import ninja.unagi.ui.DeviceDetailActivity
import ninja.unagi.util.DebugLog
import ninja.unagi.util.NotificationPermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DeviceAlertNotifier(
  private val context: Context
) {
  private val appContext = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var activeRingtone: Ringtone? = null

  fun notifyMatch(match: AlertMatch, observation: AlertObservation) {
    playSound(match.rule)
    postNotification(match, observation)
  }

  private fun playSound(rule: ninja.unagi.data.AlertRuleEntity) {
    val preset = AlertSoundPreset.fromStorageValue(rule.soundPreset) ?: AlertSoundPreset.PING
    val ringtone = runCatching {
      RingtoneManager.getRingtone(appContext, preset.resolveUri())
    }.getOrNull() ?: return

    runCatching {
      activeRingtone?.stop()
      activeRingtone = ringtone
      ringtone.play()
      scope.launch {
        delay(preset.stopAfterMs)
        if (activeRingtone === ringtone) {
          ringtone.stop()
          activeRingtone = null
        } else {
          ringtone.stop()
        }
      }
    }.onFailure { error ->
      DebugLog.log(
        "Alert sound failed: ${error.message}",
        level = android.util.Log.WARN,
        throwable = error
      )
    }
  }

  private fun postNotification(match: AlertMatch, observation: AlertObservation) {
    ensureChannel()
    if (!NotificationPermissionHelper.canPostNotifications(appContext)) {
      DebugLog.log("Notification permission missing; alert notification skipped", level = android.util.Log.WARN)
      return
    }

    val contentIntent = DeviceDetailActivity.intent(appContext, observation.deviceKey).apply {
      flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val pendingIntent = PendingIntent.getActivity(
      appContext,
      notificationId(match, observation),
      contentIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val title = "${match.rule.emoji} ${observation.displayTitle()}"
    val addressPart = observation.formattedAddress ?: "No MAC visible"
    val vendorPart = observation.vendorName ?: "Unknown vendor"
    val message = "${match.reason} • $addressPart • $vendorPart"

    val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_unagi_status)
      .setContentTitle(title)
      .setContentText(message)
      .setStyle(NotificationCompat.BigTextStyle().bigText("$message\nSource: ${observation.source}"))
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_EVENT)
      .setContentIntent(pendingIntent)
      .setAutoCancel(true)
      .build()

    runCatching {
      NotificationManagerCompat.from(appContext).notify(notificationId(match, observation), notification)
    }.onFailure { error ->
      DebugLog.log(
        "Alert notification failed: ${error.message}",
        level = android.util.Log.WARN,
        throwable = error
      )
    }
  }

  private fun ensureChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      return
    }

    val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
    val channel = NotificationChannel(
      CHANNEL_ID,
      appContext.getString(R.string.alert_notification_channel_name),
      NotificationManager.IMPORTANCE_HIGH
    ).apply {
      description = appContext.getString(R.string.alert_notification_channel_description)
      setSound(null, null)
    }
    manager.createNotificationChannel(channel)
  }

  private fun notificationId(match: AlertMatch, observation: AlertObservation): Int {
    return (31 * match.rule.id.hashCode() + observation.deviceKey.hashCode()).absoluteValue()
  }

  private fun Int.absoluteValue(): Int {
    return if (this == Int.MIN_VALUE) 0 else kotlin.math.abs(this)
  }

  companion object {
    private const val CHANNEL_ID = "device_alerts"
  }
}
