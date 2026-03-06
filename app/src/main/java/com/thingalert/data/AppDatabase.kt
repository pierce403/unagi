package com.thingalert.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
  entities = [DeviceEntity::class, SightingEntity::class],
  version = 1,
  exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
  abstract fun deviceDao(): DeviceDao
  abstract fun sightingDao(): SightingDao

  companion object {
    fun build(context: Context): AppDatabase {
      return Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        "thingalert.db"
      )
        .fallbackToDestructiveMigration()
        .build()
    }
  }
}
