package ninja.unagi.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import ninja.unagi.alerts.AlertObservation
import ninja.unagi.alerts.DeviceAlertMatcher
import ninja.unagi.ThingAlertApp
import ninja.unagi.scan.ContinuousScanPreferences
import ninja.unagi.scan.ScanState
import ninja.unagi.util.BluetoothAddressTools
import ninja.unagi.util.BluetoothAssignedNumbersProvider
import ninja.unagi.util.DeviceNoteFormatter
import ninja.unagi.util.DeviceIdentityPresenter
import ninja.unagi.util.Formatters
import ninja.unagi.util.ObservationMetadataParser
import ninja.unagi.util.VendorPrefixRegistryProvider

@OptIn(FlowPreview::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
  private val thingAlertApp = app as ThingAlertApp
  private val repository = thingAlertApp.repository
  private val alertRuleRepository = thingAlertApp.alertRuleRepository
  private val scanner = thingAlertApp.scanController
  private val vendorRegistry = VendorPrefixRegistryProvider.get(app)
  private val assignedNumbers = BluetoothAssignedNumbersProvider.get(app)

  private val filterQuery = MutableStateFlow("")
  private val deviceGroup = MutableStateFlow(DeviceListGroup.ALL)
  private val sortMode = MutableStateFlow(SortMode.RECENT)
  private val liveOnly = MutableStateFlow(false)
  private val unknownOnly = MutableStateFlow(false)
  private val starredOnly = MutableStateFlow(false)
  private val liveTicker = flow {
    while (true) {
      emit(System.currentTimeMillis())
      delay(LiveDeviceWindow.TICK_MS)
    }
  }
    .onStart { emit(System.currentTimeMillis()) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), System.currentTimeMillis())
  private val observedDevices = repository.observeDevices()
    .conflate()
    .sample(DEVICE_LIST_SAMPLE_MS)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
  private val enabledAlertRules = alertRuleRepository.observeEnabledRules()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val scanState: StateFlow<ScanState> = scanner.scanState
  val selectedDeviceGroup: StateFlow<DeviceListGroup> = deviceGroup

  private val devicesFlow = observedDevices
    .combine(enabledAlertRules) { entities, rules -> entities to rules }
    .map { (entities, rules) ->
      withContext(Dispatchers.Default) {
        entities.map {
          val metadata = ObservationMetadataParser.parse(it.lastMetadataJson)
          val identity = DeviceIdentityPresenter.present(
            displayName = it.displayName,
            address = it.lastAddress,
            metadata = metadata,
            vendorRegistry = vendorRegistry,
            assignedNumbers = assignedNumbers
          )
          val deviceNote = DeviceNoteFormatter.normalize(it.userCustomName)
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
          metaParts += "Nearby since: ${Formatters.formatTimestamp(it.lastSightingAt)}"
          metaParts += Formatters.formatSightingsCount(it.sightingsCount)
          val displayTitle = DeviceNoteFormatter.appendToTitle(identity.title, deviceNote)
          val searchParts = buildList {
            add(identity.title)
            add(displayTitle)
            deviceNote?.let(::add)
            it.lastAddress?.let(::add)
            identity.vendorName?.let(::add)
            identity.vendorSource?.let(::add)
            identity.addressTypeLabel?.let(::add)
            identity.classificationLabel?.let(::add)
            addAll(identity.classificationEvidence)
            addAll(identity.metadataSummary.searchTerms)
          }
          val matchesEnabledAlert = DeviceAlertMatcher.findMatches(
            rules = rules,
            observation = AlertObservation(
              deviceKey = it.deviceKey,
              displayName = it.displayName,
              advertisedName = metadata.advertisedName,
              systemName = metadata.systemName,
              address = it.lastAddress,
              vendorName = identity.vendorName,
              source = metadata.source ?: metadata.transport.label
            )
          ).isNotEmpty()
          DeviceListItem(
            deviceKey = it.deviceKey,
            displayName = it.displayName,
            displayTitle = displayTitle,
            deviceNote = deviceNote,
            metaLine = metaParts.joinToString(" • "),
            searchText = searchParts.joinToString("\n"),
            sortTimestamp = it.lastSightingAt,
            lastSeen = it.lastSeen,
            lastRssi = it.lastRssi,
            sightingsCount = it.sightingsCount,
            starred = it.starred,
            matchesEnabledAlert = matchesEnabledAlert,
            lastAddress = it.lastAddress,
            vendorName = identity.vendorName,
            sharedFromGroupIds = it.sharedFromGroupIds
          )
        }
      }
    }

  private val filteredFlow = devicesFlow
    .combine(filterQuery) { list, query ->
      if (query.isBlank()) {
        list
      } else {
        val normalizedAddressFragment = BluetoothAddressTools.normalizeFilterFragment(query)
        list.filter { item ->
          item.searchText.contains(query, ignoreCase = true) ||
            (item.lastAddress?.contains(query, ignoreCase = true) == true) ||
            (
              normalizedAddressFragment != null &&
                BluetoothAddressTools.normalizeAddress(item.lastAddress)
                  ?.contains(normalizedAddressFragment) == true
              )
        }
      }
    }
    .combine(deviceGroup) { list, group -> list to group }
    .combine(liveTicker) { (list, group), now ->
      list.filter { item -> DeviceListFilters.matchesGroup(item, group, now) }
    }
    .combine(unknownOnly) { list, unknown ->
      if (unknown) list.filter { it.displayName.isNullOrBlank() } else list
    }
    .combine(starredOnly) { list, starred ->
      if (starred) list.filter { it.starred } else list
    }
    .combine(liveOnly) { list, live ->
      list to live
    }
    .combine(liveTicker) { (list, live), now ->
      if (live) list.filter { LiveDeviceWindow.isLive(it.lastSeen, now) } else list
    }

  val devices: StateFlow<List<DeviceListItem>> = filteredFlow
    .combine(sortMode) { list, sort ->
      when (sort) {
        SortMode.RECENT -> list.sortedByDescending { it.sortTimestamp }
        SortMode.STRONGEST -> list.sortedByDescending { it.lastRssi }
        SortMode.NAME -> list.sortedBy { it.displayTitle.lowercase() }
      }
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val liveDeviceCount: StateFlow<Int> = observedDevices
    .combine(liveTicker) { devices, now ->
      devices.count { LiveDeviceWindow.isLive(it.lastSeen, now) }
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

  fun updateQuery(query: String) {
    filterQuery.value = query
  }

  fun setDeviceGroup(group: DeviceListGroup) {
    deviceGroup.value = group
  }

  fun updateSortMode(mode: SortMode) {
    sortMode.value = mode
  }

  fun setLiveOnly(live: Boolean) {
    liveOnly.value = live
  }

  fun setUnknownOnly(unknown: Boolean) {
    unknownOnly.value = unknown
  }

  fun setStarredOnly(starred: Boolean) {
    starredOnly.value = starred
  }

  fun setStarred(deviceKey: String, starred: Boolean) {
    viewModelScope.launch {
      repository.setStarred(deviceKey, starred)
    }
  }

  fun setDeviceNote(deviceKey: String, note: String?) {
    viewModelScope.launch {
      repository.setUserCustomName(deviceKey, note)
    }
  }

  fun startScan() {
    scanner.startScan()
  }

  fun stopScan() {
    scanner.stopScan()
  }

  fun refreshPreflightState() {
    scanner.refreshState()
  }

  override fun onCleared() {
    if (!ContinuousScanPreferences.isEnabled(getApplication())) {
      scanner.stopScan()
    }
    super.onCleared()
  }

  companion object {
    private const val DEVICE_LIST_SAMPLE_MS = 300L
  }
}
