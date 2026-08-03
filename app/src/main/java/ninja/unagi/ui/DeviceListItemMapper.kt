package ninja.unagi.ui

import java.util.Locale
import ninja.unagi.alerts.AlertObservation
import ninja.unagi.alerts.DeviceAlertMatcher
import ninja.unagi.data.AlertRuleEntity
import ninja.unagi.data.DeviceEntity
import ninja.unagi.util.BluetoothAddressTools
import ninja.unagi.util.BluetoothAssignedNumbersRegistry
import ninja.unagi.util.DeviceIdentityPresenter
import ninja.unagi.util.DeviceNoteFormatter
import ninja.unagi.util.Formatters
import ninja.unagi.util.ObservationMetadataParser
import ninja.unagi.util.VendorPrefixRegistry

internal class DeviceListItemMapper(
  private val vendorRegistry: VendorPrefixRegistry,
  private val assignedNumbers: BluetoothAssignedNumbersRegistry
) {
  private data class PresentationKey(
    val deviceKey: String,
    val displayName: String?,
    val lastAddress: String?,
    val lastSightingAt: Long,
    val sightingsCount: Int,
    val lastMetadataJson: String?,
    val starred: Boolean,
    val userCustomName: String?,
    val sharedFromGroupIds: String?
  )

  private data class CachedItem(
    val key: PresentationKey,
    val item: DeviceListItem
  )

  private val cache = mutableMapOf<String, CachedItem>()
  private var cachedRules: List<AlertRuleEntity>? = null

  fun map(
    entities: List<DeviceEntity>,
    rules: List<AlertRuleEntity>
  ): List<DeviceListItem> {
    if (cachedRules != rules) {
      cachedRules = rules.toList()
      cache.clear()
    }

    val activeKeys = HashSet<String>(entities.size)
    val items = ArrayList<DeviceListItem>(entities.size)
    entities.forEach { entity ->
      activeKeys += entity.deviceKey
      val presentationKey = entity.presentationKey()
      val cached = cache[entity.deviceKey]
      val stableItem = if (cached?.key == presentationKey) {
        cached.item
      } else {
        buildItem(entity, rules)
      }
      val currentItem = if (
        stableItem.lastSeen != entity.lastSeen || stableItem.lastRssi != entity.lastRssi
      ) {
        stableItem.copy(lastSeen = entity.lastSeen, lastRssi = entity.lastRssi)
      } else {
        stableItem
      }
      cache[entity.deviceKey] = CachedItem(presentationKey, currentItem)
      items += currentItem
    }
    cache.keys.retainAll(activeKeys)
    return items
  }

  private fun DeviceEntity.presentationKey(): PresentationKey {
    return PresentationKey(
      deviceKey = deviceKey,
      displayName = displayName,
      lastAddress = lastAddress,
      lastSightingAt = lastSightingAt,
      sightingsCount = sightingsCount,
      lastMetadataJson = lastMetadataJson,
      starred = starred,
      userCustomName = userCustomName,
      sharedFromGroupIds = sharedFromGroupIds
    )
  }

  private fun buildItem(
    entity: DeviceEntity,
    rules: List<AlertRuleEntity>
  ): DeviceListItem {
    val metadata = ObservationMetadataParser.parse(entity.lastMetadataJson)
    val identity = DeviceIdentityPresenter.present(
      displayName = entity.displayName,
      address = entity.lastAddress,
      metadata = metadata,
      vendorRegistry = vendorRegistry,
      assignedNumbers = assignedNumbers
    )
    val deviceNote = DeviceNoteFormatter.normalize(entity.userCustomName)
    val metaParts = mutableListOf<String>()
    identity.classificationLabel?.let { label ->
      val confidenceSuffix = identity.classificationConfidenceLabel?.let { " ($it)" }.orEmpty()
      metaParts += "Likely: $label$confidenceSuffix"
    }
    identity.addressTypeLabel?.let(metaParts::add)
    identity.nameSourceLabel?.let(metaParts::add)
    identity.vendorName?.let { vendor ->
      val confidenceSuffix = identity.vendorConfidenceLabel?.let { " ($it)" }.orEmpty()
      metaParts += "Vendor: $vendor$confidenceSuffix"
    }
    metaParts += identity.metadataSummary.listLabels
      .filterNot { label -> metaParts.any { it.equals(label, ignoreCase = true) } }
      .take(2)
    metaParts += "Nearby since: ${Formatters.formatTimestamp(entity.lastSightingAt)}"
    metaParts += Formatters.formatSightingsCount(entity.sightingsCount)

    val displayTitle = DeviceNoteFormatter.appendToTitle(identity.title, deviceNote)
    val normalizedAddress = BluetoothAddressTools.normalizeAddress(entity.lastAddress)
    val searchText = buildList {
      add(identity.title)
      add(displayTitle)
      deviceNote?.let(::add)
      entity.lastAddress?.let(::add)
      normalizedAddress?.let(::add)
      identity.vendorName?.let(::add)
      identity.vendorSource?.let(::add)
      identity.addressTypeLabel?.let(::add)
      identity.classificationLabel?.let(::add)
      addAll(identity.classificationEvidence)
      addAll(identity.metadataSummary.searchTerms)
    }.joinToString("\n").lowercase(Locale.ROOT)

    val matchesEnabledAlert = DeviceAlertMatcher.findMatches(
      rules = rules,
      observation = AlertObservation(
        deviceKey = entity.deviceKey,
        displayName = entity.displayName,
        advertisedName = metadata.advertisedName,
        systemName = metadata.systemName,
        address = entity.lastAddress,
        vendorName = identity.vendorName,
        source = metadata.source ?: metadata.transport.label,
        manufacturerCompanyIds = metadata.manufacturerData.keys,
        serviceUuids = metadata.serviceUuids
      )
    ).isNotEmpty()

    return DeviceListItem(
      deviceKey = entity.deviceKey,
      displayName = entity.displayName,
      displayTitle = displayTitle,
      deviceNote = deviceNote,
      metaLine = metaParts.joinToString(" • "),
      searchText = searchText,
      sortName = displayTitle.lowercase(Locale.ROOT),
      normalizedAddress = normalizedAddress,
      sortTimestamp = entity.lastSightingAt,
      lastSeen = entity.lastSeen,
      lastRssi = entity.lastRssi,
      sightingsCount = entity.sightingsCount,
      starred = entity.starred,
      matchesEnabledAlert = matchesEnabledAlert,
      lastAddress = entity.lastAddress,
      vendorName = identity.vendorName,
      sharedFromGroupIds = entity.sharedFromGroupIds
    )
  }
}
