package com.thingalert.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object PermissionsHelper {
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
}
