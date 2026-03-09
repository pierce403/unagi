package ninja.unagi.group

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import ninja.unagi.data.DeviceDao
import ninja.unagi.data.DeviceEntity
import ninja.unagi.data.DeviceEnrichmentDao
import ninja.unagi.data.AlertRuleDao
import ninja.unagi.data.SightingDao

/**
 * Merges imported bundle data into the local database using additive-only semantics.
 * Never deletes or downgrades local data.
 *
 * Fix #6: uses max() for counts instead of sum to prevent inflation on re-exchange.
 * Fix #8: batch-loads existing sighting keys for O(1) dedup lookups.
 * Fix #9: entire merge runs inside a Room transaction for atomicity.
 */
class DataMerger(
  private val database: RoomDatabase,
  private val deviceDao: DeviceDao,
  private val sightingDao: SightingDao,
  private val alertRuleDao: AlertRuleDao,
  private val enrichmentDao: DeviceEnrichmentDao
) {
  suspend fun merge(payload: BundlePayload): MergeResult {
    return database.withTransaction {
      mergeInTransaction(payload)
    }
  }

  private suspend fun mergeInTransaction(payload: BundlePayload): MergeResult {
    var devicesAdded = 0
    var devicesUpdated = 0
    var sightingsAdded = 0
    var alertRulesAdded = 0
    var enrichmentsAdded = 0

    // Merge devices
    for (imported in payload.devices) {
      val local = deviceDao.getDevice(imported.deviceKey)
      if (local == null) {
        deviceDao.upsertDevice(imported)
        devicesAdded++
      } else {
        val merged = mergeDevice(local, imported)
        if (merged != local) {
          deviceDao.upsertDevice(merged)
          devicesUpdated++
        }
      }
    }

    // Fix #8: batch-load existing sighting keys for imported device keys
    if (payload.sightings.isNotEmpty()) {
      val importedDeviceKeys = payload.sightings.map { it.deviceKey }.distinct()
      // Room @Query IN clauses support up to 999 items; chunk if needed
      val existingKeys = importedDeviceKeys.chunked(500).flatMap { chunk ->
        sightingDao.getExistingSightingKeys(chunk)
      }.toHashSet()

      for (imported in payload.sightings) {
        val key = "${imported.deviceKey}|${imported.timestamp}"
        if (key !in existingKeys) {
          sightingDao.insertSighting(imported.copy(id = 0))
          existingKeys.add(key)
          sightingsAdded++
        }
      }
    }

    // Merge alert rules (additive only, skip if matching type+pattern exists)
    if (payload.alertRules.isNotEmpty()) {
      val existingRules = alertRuleDao.getRules()
      val existingRuleKeys = existingRules.map { "${it.matchType}|${it.matchPattern}" }.toSet()
      for (imported in payload.alertRules) {
        val key = "${imported.matchType}|${imported.matchPattern}"
        if (key !in existingRuleKeys) {
          alertRuleDao.insert(imported.copy(id = 0))
          alertRulesAdded++
        }
      }
    }

    // Merge enrichments (keep newer)
    for (imported in payload.enrichments) {
      val local = enrichmentDao.getEnrichment(imported.deviceKey)
      if (local == null || imported.lastQueryTimestamp > local.lastQueryTimestamp) {
        enrichmentDao.upsertEnrichment(imported)
        enrichmentsAdded++
      }
    }

    // Apply starred device keys (only add stars, never remove)
    for (deviceKey in payload.starredDeviceKeys) {
      val device = deviceDao.getDevice(deviceKey)
      if (device != null && !device.starred) {
        deviceDao.setStarred(deviceKey, true)
      }
    }

    return MergeResult(
      devicesAdded = devicesAdded,
      devicesUpdated = devicesUpdated,
      sightingsAdded = sightingsAdded,
      alertRulesAdded = alertRulesAdded,
      enrichmentsAdded = enrichmentsAdded
    )
  }

  /**
   * Fix #6: use max() for counts instead of sum to prevent inflation on re-exchange.
   */
  private fun mergeDevice(local: DeviceEntity, imported: DeviceEntity): DeviceEntity {
    return local.copy(
      firstSeen = minOf(local.firstSeen, imported.firstSeen),
      lastSeen = maxOf(local.lastSeen, imported.lastSeen),
      lastSightingAt = maxOf(local.lastSightingAt, imported.lastSightingAt),
      sightingsCount = maxOf(local.sightingsCount, imported.sightingsCount),
      observationCount = maxOf(local.observationCount, imported.observationCount),
      lastRssi = if (imported.lastSeen > local.lastSeen) imported.lastRssi else local.lastRssi,
      rssiMin = minOf(local.rssiMin, imported.rssiMin),
      rssiMax = maxOf(local.rssiMax, imported.rssiMax),
      rssiAvg = (local.rssiAvg + imported.rssiAvg) / 2.0,
      lastMetadataJson = if (imported.lastSeen > local.lastSeen) imported.lastMetadataJson else local.lastMetadataJson,
      starred = local.starred || imported.starred,
      displayName = local.displayName ?: imported.displayName,
      lastAddress = if (imported.lastSeen > local.lastSeen) imported.lastAddress else local.lastAddress,
      userCustomName = local.userCustomName ?: imported.userCustomName
    )
  }
}

data class MergeResult(
  val devicesAdded: Int,
  val devicesUpdated: Int,
  val sightingsAdded: Int,
  val alertRulesAdded: Int,
  val enrichmentsAdded: Int
) {
  val totalItems get() = devicesAdded + devicesUpdated + sightingsAdded + alertRulesAdded + enrichmentsAdded
}
