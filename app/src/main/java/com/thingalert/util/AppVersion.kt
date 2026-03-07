package com.thingalert.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

data class AppVersionInfo(
  val versionName: String,
  val versionCode: Long
) {
  val visibleLabel: String
    get() = "VERSION v$versionName • BUILD $versionCode"
}

object AppVersion {
  @Suppress("DEPRECATION")
  fun read(context: Context): AppVersionInfo {
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      context.packageManager.getPackageInfo(
        context.packageName,
        PackageManager.PackageInfoFlags.of(0)
      )
    } else {
      context.packageManager.getPackageInfo(context.packageName, 0)
    }

    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      packageInfo.longVersionCode
    } else {
      packageInfo.versionCode.toLong()
    }

    return AppVersionInfo(
      versionName = packageInfo.versionName ?: "unknown",
      versionCode = versionCode
    )
  }
}
