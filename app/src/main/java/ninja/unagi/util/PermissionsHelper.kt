package ninja.unagi.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionsHelper {
  private const val PREFS_NAME = "unagi_permissions"
  private const val KEY_SCAN_PERMISSION_REQUESTED = "scan_permission_requested"
  private const val KEY_BACKGROUND_LOCATION_REQUESTED = "background_location_requested"

  fun requiredPermissions(activeScanning: Boolean = false): List<String> {
    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      mutableListOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
      )
    } else {
      mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
      )
    }
    if (activeScanning && requiresBackgroundLocationForActiveScan()) {
      permissions += Manifest.permission.ACCESS_BACKGROUND_LOCATION
    }
    return permissions
  }

  fun foregroundPermissions(activeScanning: Boolean = false): List<String> {
    return requiredPermissions(activeScanning)
      .filterNot { it == Manifest.permission.ACCESS_BACKGROUND_LOCATION }
  }

  fun missingPermissions(context: Context, activeScanning: Boolean = false): List<String> {
    return requiredPermissions(activeScanning).filter { permission ->
      ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
    }
  }

  fun hasPermissions(context: Context, activeScanning: Boolean = false): Boolean {
    return missingPermissions(context, activeScanning).isEmpty()
  }

  fun requiresBackgroundLocationForActiveScan(): Boolean {
    return Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Build.VERSION_CODES.R
  }

  fun hasBackgroundLocationPermission(context: Context): Boolean {
    if (!requiresBackgroundLocationForActiveScan()) {
      return true
    }
    return ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.ACCESS_BACKGROUND_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
  }

  fun markPermissionRequestAttempted(context: Context) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putBoolean(KEY_SCAN_PERMISSION_REQUESTED, true)
      .apply()
  }

  fun markBackgroundLocationRequestAttempted(context: Context) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putBoolean(KEY_BACKGROUND_LOCATION_REQUESTED, true)
      .apply()
  }

  fun shouldOpenAppSettings(activity: Activity, activeScanning: Boolean = false): Boolean {
    val missing = missingPermissions(activity, activeScanning)
    if (missing.isEmpty()) {
      return false
    }

    val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val hasRequestedBefore = prefs.getBoolean(KEY_SCAN_PERMISSION_REQUESTED, false)
    val backgroundRequestedBefore = prefs.getBoolean(KEY_BACKGROUND_LOCATION_REQUESTED, false)

    return hasRequestedBefore && missing.any { permission ->
      val requestedBefore = when (permission) {
        Manifest.permission.ACCESS_BACKGROUND_LOCATION -> backgroundRequestedBefore
        else -> hasRequestedBefore
      }
      requestedBefore && !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }
  }

  fun missingPermissionLabels(context: Context, activeScanning: Boolean = false): List<String> {
    return missingPermissions(context, activeScanning)
      .mapNotNull { permission ->
        when (permission) {
          Manifest.permission.BLUETOOTH_SCAN,
          Manifest.permission.BLUETOOTH_CONNECT -> "Nearby devices"
          Manifest.permission.ACCESS_FINE_LOCATION,
          Manifest.permission.ACCESS_COARSE_LOCATION -> "Location"
          Manifest.permission.ACCESS_BACKGROUND_LOCATION -> "Background location"
          else -> null
        }
      }
      .distinct()
  }

  fun isLocationServicesRequired(): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
  }

  fun isLocationServicesEnabled(context: Context): Boolean {
    if (!isLocationServicesRequired()) {
      return true
    }

    val manager = context.getSystemService(LocationManager::class.java) ?: return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      manager.isLocationEnabled
    } else {
      @Suppress("DEPRECATION")
      manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
        manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
  }
}
