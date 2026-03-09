package ninja.unagi.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
  entities = [
    DeviceEntity::class,
    SightingEntity::class,
    AlertRuleEntity::class,
    DeviceEnrichmentEntity::class,
    AffinityGroupEntity::class,
    AffinityGroupMemberEntity::class,
    AffinityImportLogEntity::class
  ],
  version = 8,
  exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
  abstract fun deviceDao(): DeviceDao
  abstract fun sightingDao(): SightingDao
  abstract fun alertRuleDao(): AlertRuleDao
  abstract fun deviceEnrichmentDao(): DeviceEnrichmentDao
  abstract fun affinityGroupDao(): AffinityGroupDao

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

    private val MIGRATION_4_5 = object : Migration(4, 5) {
      override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
          """
          ALTER TABLE `devices` ADD COLUMN `userCustomName` TEXT DEFAULT NULL
          """.trimIndent()
        )
      }
    }

    private val MIGRATION_5_6 = object : Migration(5, 6) {
      override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
          """
          CREATE TABLE IF NOT EXISTS `affinity_groups` (
            `groupId` TEXT NOT NULL,
            `groupName` TEXT NOT NULL,
            `createdAt` INTEGER NOT NULL,
            `myMemberId` TEXT NOT NULL,
            `myDisplayName` TEXT NOT NULL,
            `groupKeyWrapped` TEXT NOT NULL,
            `keyEpoch` INTEGER NOT NULL,
            `sharingConfigJson` TEXT NOT NULL,
            PRIMARY KEY(`groupId`)
          )
          """.trimIndent()
        )
        database.execSQL(
          """
          CREATE TABLE IF NOT EXISTS `affinity_group_members` (
            `groupId` TEXT NOT NULL,
            `memberId` TEXT NOT NULL,
            `displayName` TEXT NOT NULL,
            `joinedAt` INTEGER NOT NULL,
            `lastSeenEpoch` INTEGER NOT NULL,
            `publicKeyBase64` TEXT,
            `revoked` INTEGER NOT NULL,
            PRIMARY KEY(`groupId`, `memberId`)
          )
          """.trimIndent()
        )
        database.execSQL(
          """
          CREATE TABLE IF NOT EXISTS `affinity_import_log` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `groupId` TEXT NOT NULL,
            `senderId` TEXT NOT NULL,
            `exportTimestamp` INTEGER NOT NULL,
            `importedAt` INTEGER NOT NULL,
            `itemCounts` TEXT NOT NULL
          )
          """.trimIndent()
        )
        database.execSQL(
          """
          CREATE UNIQUE INDEX IF NOT EXISTS `index_affinity_import_log_groupId_senderId_exportTimestamp`
          ON `affinity_import_log` (`groupId`, `senderId`, `exportTimestamp`)
          """.trimIndent()
        )
      }
    }

    private val MIGRATION_6_7 = object : Migration(6, 7) {
      override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
          """
          ALTER TABLE `affinity_groups` ADD COLUMN `requireEcdh` INTEGER NOT NULL DEFAULT 0
          """.trimIndent()
        )
      }
    }

    private val MIGRATION_7_8 = object : Migration(7, 8) {
      override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
          """
          ALTER TABLE `devices` ADD COLUMN `sharedFromGroupIds` TEXT DEFAULT NULL
          """.trimIndent()
        )
      }
    }

    fun build(context: Context): AppDatabase {
      val passphrase = DatabaseKeyManager.getOrCreatePassphrase(context)
      val openMode = DatabaseMigrationHelper.prepareOpenMode(context, passphrase)
      val builder = Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        "thingalert.db"
      )
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
        .fallbackToDestructiveMigration()
      if (openMode == DatabaseMigrationHelper.OpenMode.ENCRYPTED) {
        System.loadLibrary("sqlcipher")
        builder.openHelperFactory(SupportOpenHelperFactory(passphrase))
      }
      return builder.build()
    }
  }
}
