package com.thingalert.util

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

  fun requiredPermissions(): List<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      listOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
      )
    } else {
      listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
  }

  fun missingPermissions(context: Context): List<String> {
    return requiredPermissions().filter { permission ->
      ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
    }
  }

  fun hasPermissions(context: Context): Boolean {
    return missingPermissions(context).isEmpty()
  }

  fun markPermissionRequestAttempted(context: Context) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putBoolean(KEY_SCAN_PERMISSION_REQUESTED, true)
      .apply()
  }

  fun shouldOpenAppSettings(activity: Activity): Boolean {
    val missing = missingPermissions(activity)
    if (missing.isEmpty()) {
      return false
    }

    val hasRequestedBefore = activity
      .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .getBoolean(KEY_SCAN_PERMISSION_REQUESTED, false)

    return hasRequestedBefore && missing.any { permission ->
      !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }
  }

  fun missingPermissionLabels(context: Context): List<String> {
    return missingPermissions(context)
      .mapNotNull { permission ->
        when (permission) {
          Manifest.permission.BLUETOOTH_SCAN,
          Manifest.permission.BLUETOOTH_CONNECT -> "Nearby devices"
          Manifest.permission.ACCESS_FINE_LOCATION -> "Location"
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
