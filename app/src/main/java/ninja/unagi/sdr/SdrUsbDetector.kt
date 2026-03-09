package ninja.unagi.sdr

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import ninja.unagi.util.DebugLog

object SdrUsbDetector {
  const val ACTION_USB_PERMISSION = "ninja.unagi.USB_PERMISSION"

  private val KNOWN_DEVICES = setOf(
    0x0BDA to 0x2838,  // RTL2832U (Nooelec NESDR SMArt v5 and most RTL-SDR dongles)
    0x0BDA to 0x2832   // RTL2832U alternate product ID
  )

  fun findSdrDevice(context: Context): UsbDevice? {
    val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
    return usbManager.deviceList.values.firstOrNull { device ->
      (device.vendorId to device.productId) in KNOWN_DEVICES
    }
  }

  fun hasPermission(context: Context, device: UsbDevice): Boolean {
    val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
    return usbManager.hasPermission(device)
  }

  fun requestPermission(context: Context, device: UsbDevice) {
    val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
    val intent = Intent(ACTION_USB_PERMISSION).apply {
      setPackage(context.packageName)
    }
    val permissionIntent = PendingIntent.getBroadcast(
      context,
      0,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )
    usbManager.requestPermission(device, permissionIntent)
    DebugLog.log("Requesting USB permission for SDR device: vendor=0x${"%04X".format(device.vendorId)} product=0x${"%04X".format(device.productId)}")
  }

  fun registerReceiver(context: Context, onAttached: () -> Unit, onDetached: () -> Unit, onPermissionResult: (Boolean) -> Unit): BroadcastReceiver {
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
          UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
            val device = usbDeviceFromIntent(intent) ?: return
            if ((device.vendorId to device.productId) in KNOWN_DEVICES) {
              DebugLog.log("SDR USB device attached")
              onAttached()
            }
          }
          UsbManager.ACTION_USB_DEVICE_DETACHED -> {
            val device = usbDeviceFromIntent(intent) ?: return
            if ((device.vendorId to device.productId) in KNOWN_DEVICES) {
              DebugLog.log("SDR USB device detached")
              onDetached()
            }
          }
          ACTION_USB_PERMISSION -> {
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            DebugLog.log("SDR USB permission result: granted=$granted")
            onPermissionResult(granted)
          }
        }
      }
    }

    val filter = IntentFilter().apply {
      addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
      addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
      addAction(ACTION_USB_PERMISSION)
    }
    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    return receiver
  }

  fun unregisterReceiver(context: Context, receiver: BroadcastReceiver) {
    try {
      context.unregisterReceiver(receiver)
    } catch (_: IllegalArgumentException) {
      // Already unregistered
    }
  }

  fun deviceDescription(device: UsbDevice): String {
    return buildString {
      append("USB SDR: ")
      device.productName?.let { append(it) } ?: append("RTL-SDR")
      append(" (vendor=0x${"%04X".format(device.vendorId)}, product=0x${"%04X".format(device.productId)})")
    }
  }

  private fun usbDeviceFromIntent(intent: Intent): UsbDevice? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
      @Suppress("DEPRECATION")
      intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }
  }
}
