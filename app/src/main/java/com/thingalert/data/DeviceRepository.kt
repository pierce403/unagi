package com.thingalert.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

class DeviceRepository(
  private val database: AppDatabase,
  private val deviceDao: DeviceDao,
  private val sightingDao: SightingDao
) {
  private val retentionDays = 30L

  fun observeDevices(): Flow<List<DeviceEntity>> = deviceDao.observeDevices()

  fun observeDevice(deviceKey: String): Flow<DeviceEntity?> = deviceDao.observeDevice(deviceKey)

  fun observeSightings(deviceKey: String): Flow<List<SightingEntity>> =
    sightingDao.observeSightings(deviceKey)

  suspend fun recordObservation(observation: DeviceObservation) {
    database.withTransaction {
      com.thingalert.util.DebugLog.log(
        "Record observation key=${observation.deviceKey.take(8)} name=${observation.name ?: "unknown"} " +
          "rssi=${observation.rssi}"
      )
      val existing = deviceDao.getDevice(observation.deviceKey)
      val updated = if (existing == null) {
        DeviceEntity(
          deviceKey = observation.deviceKey,
          displayName = observation.name,
          lastAddress = observation.address,
          firstSeen = observation.timestamp,
          lastSeen = observation.timestamp,
          sightingsCount = 1,
          lastRssi = observation.rssi,
          rssiMin = observation.rssi,
          rssiMax = observation.rssi,
          rssiAvg = observation.rssi.toDouble(),
          lastMetadataJson = observation.metadataJson
        )
      } else {
        val count = existing.sightingsCount + 1
        val avg = ((existing.rssiAvg * existing.sightingsCount) + observation.rssi) / count
        DeviceEntity(
          deviceKey = existing.deviceKey,
          displayName = observation.name ?: existing.displayName,
          lastAddress = observation.address ?: existing.lastAddress,
          firstSeen = minOf(existing.firstSeen, observation.timestamp),
          lastSeen = maxOf(existing.lastSeen, observation.timestamp),
          sightingsCount = count,
          lastRssi = observation.rssi,
          rssiMin = minOf(existing.rssiMin, observation.rssi),
          rssiMax = maxOf(existing.rssiMax, observation.rssi),
          rssiAvg = avg,
          lastMetadataJson = observation.metadataJson ?: existing.lastMetadataJson
        )
      }

      deviceDao.upsertDevice(updated)
      sightingDao.insertSighting(
        SightingEntity(
          deviceKey = observation.deviceKey,
          timestamp = observation.timestamp,
          rssi = observation.rssi,
          name = observation.name,
          address = observation.address,
          metadataJson = observation.metadataJson
        )
      )

      pruneIfNeeded(observation.timestamp)
    }
  }

  private suspend fun pruneIfNeeded(now: Long) {
    val threshold = now - retentionDays * 24 * 60 * 60 * 1000
    sightingDao.pruneOlderThan(threshold)
    deviceDao.deleteOlderThan(threshold)
  }
}
