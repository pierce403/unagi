package ninja.unagi.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ninja.unagi.ThingAlertApp
import ninja.unagi.scan.ContinuousScanPreferences
import ninja.unagi.scan.ScanState
import ninja.unagi.util.BluetoothAssignedNumbersProvider
import ninja.unagi.util.VendorPrefixRegistryProvider

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
  private val thingAlertApp = app as ThingAlertApp
  private val repository = thingAlertApp.repository
  private val alertRuleRepository = thingAlertApp.alertRuleRepository
  private val scanner = thingAlertApp.scanController
  private val vendorRegistry by lazy { VendorPrefixRegistryProvider.get(app) }
  private val assignedNumbers by lazy { BluetoothAssignedNumbersProvider.get(app) }
  private val itemMapper by lazy { DeviceListItemMapper(vendorRegistry, assignedNumbers) }

  private val filterQuery = MutableStateFlow("")
  private val deviceGroup = MutableStateFlow(DeviceListGroup.ALL)
  private val sortMode = MutableStateFlow(SortMode.RECENT)
  private val liveOnly = MutableStateFlow(false)
  private val unknownOnly = MutableStateFlow(false)
  private val starredOnly = MutableStateFlow(false)

  private data class PrimaryFilters(
    val query: String,
    val group: DeviceListGroup,
    val sortMode: SortMode
  )

  private data class ToggleFilters(
    val liveOnly: Boolean,
    val unknownOnly: Boolean,
    val starredOnly: Boolean
  )

  private data class TimedFilters(
    val options: DeviceListFilterOptions,
    val now: Long
  )

  private val liveTicker = flow {
    while (true) {
      emit(System.currentTimeMillis())
      delay(LiveDeviceWindow.TICK_MS)
    }
  }
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
    .mapLatest { (entities, rules) ->
      withContext(Dispatchers.Default) {
        itemMapper.map(entities, rules)
      }
    }

  private val primaryFilters = combine(
    filterQuery.debounce(FILTER_QUERY_DEBOUNCE_MS),
    deviceGroup,
    sortMode
  ) { query, group, sort ->
    PrimaryFilters(query, group, sort)
  }

  private val toggleFilters = combine(
    liveOnly,
    unknownOnly,
    starredOnly
  ) { live, unknown, starred ->
    ToggleFilters(live, unknown, starred)
  }

  private val filterOptions = primaryFilters
    .combine(toggleFilters) { primary, toggles ->
      DeviceListFilterOptions(
        query = primary.query,
        group = primary.group,
        sortMode = primary.sortMode,
        liveOnly = toggles.liveOnly,
        unknownOnly = toggles.unknownOnly,
        starredOnly = toggles.starredOnly
      )
    }
    .distinctUntilChanged()

  private val timedFilters = filterOptions
    .combine(liveTicker) { options, now ->
      TimedFilters(
        options = options,
        now = if (options.requiresLiveClock) now else 0L
      )
    }
    .distinctUntilChanged()

  val devices: StateFlow<List<DeviceListItem>> = devicesFlow
    .combine(timedFilters) { items, timed ->
      withContext(Dispatchers.Default) {
        DeviceListFilters.filterAndSort(items, timed.options, timed.now)
      }
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val liveDeviceCount: StateFlow<Int> = observedDevices
    .combine(liveTicker) { devices, now ->
      withContext(Dispatchers.Default) {
        devices.count { LiveDeviceWindow.isLive(it.lastSeen, now) }
      }
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
    private const val FILTER_QUERY_DEBOUNCE_MS = 150L
  }
}
