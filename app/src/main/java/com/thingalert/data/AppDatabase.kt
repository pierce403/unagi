package com.thingalert.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
  entities = [DeviceEntity::class, SightingEntity::class, AlertRuleEntity::class],
  version = 2,
  exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
  abstract fun deviceDao(): DeviceDao
  abstract fun sightingDao(): SightingDao
  abstract fun alertRuleDao(): AlertRuleDao

  companion object {
    private val MIGRATION_1_2 = object : Migration(1, 2) {
      override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
          """
          CREATE TABLE IF NOT EXISTS `alert_rules` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `matchType` TEXT NOT NULL,
            `matchPattern` TEXT NOT NULL,
            `displayValue` TEXT NOT NULL,
            `emoji` TEXT NOT NULL,
            `soundPreset` TEXT NOT NULL,
            `enabled` INTEGER NOT NULL,
            `createdAt` INTEGER NOT NULL
          )
          """.trimIndent()
        )
      }
    }

    fun build(context: Context): AppDatabase {
      return Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        "thingalert.db"
      )
        .addMigrations(MIGRATION_1_2)
        .fallbackToDestructiveMigration()
        .build()
    }
  }
}
