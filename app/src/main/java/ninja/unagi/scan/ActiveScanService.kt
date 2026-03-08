package ninja.unagi.scan

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ninja.unagi.R
import ninja.unagi.ThingAlertApp

class ContinuousScanService : Service() {
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private lateinit var app: ThingAlertApp
  private var scanLoopJob: Job? = null
  private var currentScanState: ScanState = ScanState.Idle
  private var currentDeviceCount = 0

  override fun onCreate() {
    super.onCreate()
    app = application as ThingAlertApp
    ensureNotificationChannel()
    observeScanUpdates()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action ?: ACTION_START) {
      ACTION_STOP -> {
        stopContinuousScanning()
        return START_NOT_STICKY
      }

      ACTION_START -> {
        ContinuousScanPreferences.setEnabled(this, true)
        startForegroundNotification()
        ensureScanLoop()
      }
    }
    return START_STICKY
  }

  override fun onDestroy() {
    scanLoopJob?.cancel()
    serviceScope.cancel()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun observeScanUpdates() {
    serviceScope.launch {
      app.scanController.scanState.collectLatest { state ->
        currentScanState = state
        updateForegroundNotification()
      }
    }

    serviceScope.launch {
      ScanDiagnosticsStore.snapshot.collectLatest { snapshot ->
        currentDeviceCount = snapshot.uniqueDeviceCount
        updateForegroundNotification()
      }
    }
  }

  private fun ensureScanLoop() {
    if (scanLoopJob?.isActive == true) {
      return
    }

    scanLoopJob = serviceScope.launch {
      while (ContinuousScanPreferences.isEnabled(this@ContinuousScanService)) {
        app.scanController.refreshState()
        when (val state = app.scanController.scanState.value) {
          is ScanState.Scanning -> delay(SCAN_LOOP_POLL_MS)
          is ScanState.MissingPermission,
          is ScanState.BluetoothOff,
          is ScanState.LocationServicesOff,
          is ScanState.Unsupported -> delay(SCAN_BLOCKED_RETRY_MS)
          is ScanState.Complete,
          is ScanState.Error,
          is ScanState.Idle -> {
            app.scanController.startScan()
            delay(
              if (state is ScanState.Error) {
                SCAN_ERROR_RETRY_MS
              } else {
                SCAN_RESTART_GRACE_MS
              }
            )
          }
        }
      }
    }
  }

  private fun stopContinuousScanning() {
    ContinuousScanPreferences.setEnabled(this, false)
    scanLoopJob?.cancel()
    scanLoopJob = null
    app.scanController.stopScan()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      stopForeground(STOP_FOREGROUND_REMOVE)
    } else {
      @Suppress("DEPRECATION")
      stopForeground(true)
    }
    stopSelf()
  }

  private fun startForegroundNotification() {
    val notification = buildNotification()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
      )
    } else {
      @Suppress("DEPRECATION")
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  private fun updateForegroundNotification() {
    val manager = getSystemService(NotificationManager::class.java) ?: return
    manager.notify(NOTIFICATION_ID, buildNotification())
  }

  private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
    .setSmallIcon(R.drawable.ic_unagi_status)
    .setContentTitle(getString(R.string.active_scan_notification_title))
    .setContentText(notificationBody())
    .setOngoing(true)
    .setOnlyAlertOnce(true)
    .setSilent(true)
    .setCategory(NotificationCompat.CATEGORY_SERVICE)
    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
    .setContentIntent(openAppPendingIntent())
    .addAction(
      0,
      getString(R.string.scan_stop),
      stopServicePendingIntent()
    )
    .build()

  private fun notificationBody(): String {
    val stateLabel = when (val state = currentScanState) {
      is ScanState.Scanning -> getString(R.string.active_scan_notification_scanning)
      is ScanState.Complete -> {
        if (state.deviceCount == 0) {
          getString(R.string.active_scan_notification_zero_results)
        } else {
          getString(R.string.active_scan_notification_results, state.deviceCount)
        }
      }
      is ScanState.MissingPermission -> getString(R.string.active_scan_notification_missing_permissions)
      is ScanState.BluetoothOff -> getString(R.string.active_scan_notification_bluetooth_off)
      is ScanState.LocationServicesOff -> getString(R.string.active_scan_notification_location_off)
      is ScanState.Unsupported -> getString(R.string.active_scan_notification_unsupported)
      is ScanState.Error -> state.message
      is ScanState.Idle -> getString(R.string.active_scan_notification_idle)
    }

    return if (currentDeviceCount > 0) {
      getString(R.string.active_scan_notification_with_count, stateLabel, currentDeviceCount)
    } else {
      stateLabel
    }
  }

  private fun openAppPendingIntent(): PendingIntent {
    val intent = Intent(this, ninja.unagi.ui.MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    return PendingIntent.getActivity(
      this,
      REQUEST_OPEN_APP,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
  }

  private fun stopServicePendingIntent(): PendingIntent {
    val intent = Intent(this, ContinuousScanService::class.java).apply {
      action = ACTION_STOP
    }
    return PendingIntent.getService(
      this,
      REQUEST_STOP_SERVICE,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
  }

  private fun ensureNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      return
    }
    val manager = getSystemService(NotificationManager::class.java) ?: return
    val channel = NotificationChannel(
      CHANNEL_ID,
      getString(R.string.active_scan_notification_channel_name),
      NotificationManager.IMPORTANCE_DEFAULT
    ).apply {
      description = getString(R.string.active_scan_notification_channel_description)
      setShowBadge(false)
      setSound(null, null)
    }
    manager.createNotificationChannel(channel)
  }

  companion object {
    private const val ACTION_START = "ninja.unagi.action.START_CONTINUOUS_SCAN"
    private const val ACTION_STOP = "ninja.unagi.action.STOP_CONTINUOUS_SCAN"
    private const val CHANNEL_ID = "active_scan_service_status"
    private const val NOTIFICATION_ID = 4101
    private const val REQUEST_OPEN_APP = 4102
    private const val REQUEST_STOP_SERVICE = 4103
    private const val SCAN_LOOP_POLL_MS = 5_000L
    private const val SCAN_BLOCKED_RETRY_MS = 10_000L
    private const val SCAN_RESTART_GRACE_MS = 1_500L
    private const val SCAN_ERROR_RETRY_MS = 4_000L

    fun start(context: Context) {
      val intent = Intent(context, ContinuousScanService::class.java).apply {
        action = ACTION_START
      }
      ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
      val intent = Intent(context, ContinuousScanService::class.java).apply {
        action = ACTION_STOP
      }
      context.startService(intent)
    }
  }
}
