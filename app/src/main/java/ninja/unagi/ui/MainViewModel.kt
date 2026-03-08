package ninja.unagi.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.flow.stateIn
import ninja.unagi.ThingAlertApp
import ninja.unagi.scan.ContinuousScanPreferences
import ninja.unagi.scan.ScanState
import ninja.unagi.util.BluetoothAddressTools
import ninja.unagi.util.BluetoothAssignedNumbersProvider
import ninja.unagi.util.DeviceIdentityPresenter
import ninja.unagi.util.Formatters
import ninja.unagi.util.VendorPrefixRegistryProvider

class MainViewModel(app: Application) : AndroidViewModel(app) {
  private companion object {
    const val LIVE_DEVICE_WINDOW_MS = 30_000L
    const val LIVE_DEVICE_TICK_MS = 5_000L
  }

  private val thingAlertApp = app as ThingAlertApp
  private val repository = thingAlertApp.repository
  private val scanner = thingAlertApp.scanController
  private val vendorRegistry = VendorPrefixRegistryProvider.get(app)
  private val assignedNumbers = BluetoothAssignedNumbersProvider.get(app)

  private val filterQuery = MutableStateFlow("")
  private val sortMode = MutableStateFlow(SortMode.RECENT)
  private val unknownOnly = MutableStateFlow(false)
  private val starredOnly = MutableStateFlow(false)
  private val observedDevices = repository.observeDevices()
    .conflate()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val scanState: StateFlow<ScanState> = scanner.scanState

  private val devicesFlow = observedDevices
    .map { entities ->
      withContext(Dispatchers.Default) {
        entities.map {
          val identity = DeviceIdentityPresenter.present(
            displayName = it.displayName,
            address = it.lastAddress,
            metadataJson = it.lastMetadataJson,
            vendorRegistry = vendorRegistry,
            assignedNumbers = assignedNumbers
          )
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
          metaParts += "Last seen: ${Formatters.formatTimestamp(it.lastSeen)}"
          metaParts += Formatters.formatSightingsCount(it.sightingsCount)
          val searchParts = buildList {
            add(identity.title)
            it.lastAddress?.let(::add)
            identity.vendorName?.let(::add)
            identity.vendorSource?.let(::add)
            identity.addressTypeLabel?.let(::add)
            identity.classificationLabel?.let(::add)
            addAll(identity.classificationEvidence)
            addAll(identity.metadataSummary.searchTerms)
          }
          DeviceListItem(
            deviceKey = it.deviceKey,
            displayName = it.displayName,
            displayTitle = identity.title,
            metaLine = metaParts.joinToString(" • "),
            searchText = searchParts.joinToString("\n"),
            lastSeen = it.lastSeen,
            lastRssi = it.lastRssi,
            sightingsCount = it.sightingsCount,
            starred = it.starred,
            lastAddress = it.lastAddress,
            vendorName = identity.vendorName
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
    .combine(unknownOnly) { list, unknown ->
      if (unknown) list.filter { it.displayName.isNullOrBlank() } else list
    }
    .combine(starredOnly) { list, starred ->
      if (starred) list.filter { it.starred } else list
    }

  val devices: StateFlow<List<DeviceListItem>> = filteredFlow
    .combine(sortMode) { list, sort ->
      when (sort) {
        SortMode.RECENT -> list.sortedByDescending { it.lastSeen }
        SortMode.STRONGEST -> list.sortedByDescending { it.lastRssi }
        SortMode.NAME -> list.sortedBy { it.displayTitle.lowercase() }
      }
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val liveDeviceCount: StateFlow<Int> = observedDevices
    .combine(
      flow {
        while (true) {
          emit(System.currentTimeMillis())
          delay(LIVE_DEVICE_TICK_MS)
        }
      }.onStart { emit(System.currentTimeMillis()) }
    ) { devices, now ->
      devices.count { now - it.lastSeen <= LIVE_DEVICE_WINDOW_MS }
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

  fun updateQuery(query: String) {
    filterQuery.value = query
  }

  fun updateSortMode(mode: SortMode) {
    sortMode.value = mode
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
}
