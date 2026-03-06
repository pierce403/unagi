package com.thingalert

import android.app.Application
import com.thingalert.data.AppDatabase
import com.thingalert.data.DeviceRepository

class ThingAlertApp : Application() {
  val database: AppDatabase by lazy { AppDatabase.build(this) }
  val repository: DeviceRepository by lazy {
    DeviceRepository(database, database.deviceDao(), database.sightingDao())
  }
}
