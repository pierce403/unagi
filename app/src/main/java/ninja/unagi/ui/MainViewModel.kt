package ninja.unagi.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ninja.unagi.ThingAlertApp
import ninja.unagi.scan.ScanController
import ninja.unagi.scan.ScanState
import ninja.unagi.util.BluetoothAssignedNumbersProvider
import ninja.unagi.util.BluetoothAddressTools
import ninja.unagi.util.DeviceIdentityPresenter
import ninja.unagi.util.Formatters
import ninja.unagi.util.VendorPrefixRegistryProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class MainViewModel(app: Application) : AndroidViewModel(app) {
  private val thingAlertApp = app as ThingAlertApp
  private val repository = thingAlertApp.repository
  private val scanner = ScanController(
    app,
    viewModelScope,
    repository,
    thingAlertApp.alertRuleRepository,
    thingAlertApp.deviceAlertNotifier
  )
  private val vendorRegistry = VendorPrefixRegistryProvider.get(app)
  private val assignedNumbers = BluetoothAssignedNumbersProvider.get(app)

  private val filterQuery = MutableStateFlow("")
  private val sortMode = MutableStateFlow(SortMode.RECENT)
  private val unknownOnly = MutableStateFlow(false)

  val scanState: StateFlow<ScanState> = scanner.scanState

  private val devicesFlow = repository.observeDevices()
    .map { entities ->
      entities.map {
        val identity = DeviceIdentityPresenter.present(
          displayName = it.displayName,
          address = it.lastAddress,
          metadataJson = it.lastMetadataJson,
          vendorRegistry = vendorRegistry,
          assignedNumbers = assignedNumbers
        )
        val metaParts = mutableListOf<String>()
        identity.nameSourceLabel?.let(metaParts::add)
        identity.vendorName?.let { metaParts += "Vendor: $it" }
          ?: identity.addressTypeLabel?.let(metaParts::add)
        metaParts += identity.metadataSummary.listLabels.take(2)
        metaParts += "Last seen: ${Formatters.formatTimestamp(it.lastSeen)}"
        metaParts += Formatters.formatSightingsCount(it.sightingsCount)
        val searchParts = buildList {
          add(identity.title)
          it.lastAddress?.let(::add)
          identity.vendorName?.let(::add)
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
          lastAddress = it.lastAddress,
          vendorName = identity.vendorName
        )
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

  val devices: StateFlow<List<DeviceListItem>> = filteredFlow
    .combine(sortMode) { list, sort ->
      when (sort) {
        SortMode.RECENT -> list.sortedByDescending { it.lastSeen }
        SortMode.STRONGEST -> list.sortedByDescending { it.lastRssi }
        SortMode.NAME -> list.sortedBy { it.displayTitle.lowercase() }
      }
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  fun updateQuery(query: String) {
    filterQuery.value = query
  }

  fun updateSortMode(mode: SortMode) {
    sortMode.value = mode
  }

  fun setUnknownOnly(unknown: Boolean) {
    unknownOnly.value = unknown
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
    scanner.stopScan()
    super.onCleared()
  }
}
