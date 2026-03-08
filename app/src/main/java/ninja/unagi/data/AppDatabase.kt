package ninja.unagi.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
  entities = [DeviceEntity::class, SightingEntity::class, AlertRuleEntity::class, DeviceEnrichmentEntity::class],
  version = 4,
  exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
  abstract fun deviceDao(): DeviceDao
  abstract fun sightingDao(): SightingDao
  abstract fun alertRuleDao(): AlertRuleDao
  abstract fun deviceEnrichmentDao(): DeviceEnrichmentDao

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

    private val MIGRATION_2_3 = object : Migration(2, 3) {
      override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
          """
          CREATE TABLE IF NOT EXISTS `device_enrichments` (
            `deviceKey` TEXT NOT NULL,
            `lastQueryTimestamp` INTEGER NOT NULL,
            `queryMethod` TEXT NOT NULL,
            `servicesPresentJson` TEXT,
            `disAvailable` INTEGER NOT NULL,
            `disReadStatus` TEXT NOT NULL,
            `manufacturerName` TEXT,
            `modelNumber` TEXT,
            `serialNumber` TEXT,
            `hardwareRevision` TEXT,
            `firmwareRevision` TEXT,
            `softwareRevision` TEXT,
            `systemId` TEXT,
            `pnpVendorIdSource` INTEGER,
            `pnpVendorId` INTEGER,
            `pnpProductId` INTEGER,
            `pnpProductVersion` INTEGER,
            `errorCode` INTEGER,
            `errorMessage` TEXT,
            `connectDurationMs` INTEGER,
            `servicesDiscovered` INTEGER NOT NULL,
            `characteristicReadSuccessCount` INTEGER NOT NULL,
            `characteristicReadFailureCount` INTEGER NOT NULL,
            `finalGattStatus` INTEGER,
            PRIMARY KEY(`deviceKey`)
          )
          """.trimIndent()
        )
      }
    }

    private val MIGRATION_3_4 = object : Migration(3, 4) {
      override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
          """
          ALTER TABLE `devices` ADD COLUMN `lastSightingAt` INTEGER NOT NULL DEFAULT 0
          """.trimIndent()
        )
        database.execSQL(
          """
          ALTER TABLE `devices` ADD COLUMN `observationCount` INTEGER NOT NULL DEFAULT 0
          """.trimIndent()
        )
        database.execSQL(
          """
          ALTER TABLE `devices` ADD COLUMN `starred` INTEGER NOT NULL DEFAULT 0
          """.trimIndent()
        )
        database.execSQL(
          """
          UPDATE `devices`
          SET `lastSightingAt` = `lastSeen`,
              `observationCount` = CASE WHEN `sightingsCount` > 0 THEN `sightingsCount` ELSE 1 END
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
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
        .fallbackToDestructiveMigration()
        .build()
    }
  }
}
