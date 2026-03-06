package com.thingalert.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thingalert.ThingAlertApp
import com.thingalert.scan.ScanController
import com.thingalert.scan.ScanState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class MainViewModel(app: Application) : AndroidViewModel(app) {
  private val repository = (app as ThingAlertApp).repository
  private val scanner = ScanController(app, viewModelScope, repository)

  private val filterQuery = MutableStateFlow("")
  private val sortMode = MutableStateFlow(SortMode.RECENT)
  private val unknownOnly = MutableStateFlow(false)

  val scanState: StateFlow<ScanState> = scanner.scanState

  private val devicesFlow = repository.observeDevices()
    .map { entities ->
      entities.map {
        DeviceListItem(
          deviceKey = it.deviceKey,
          displayName = it.displayName,
          lastSeen = it.lastSeen,
          lastRssi = it.lastRssi,
          sightingsCount = it.sightingsCount,
          lastAddress = it.lastAddress
        )
      }
    }

  private val filteredFlow = devicesFlow
    .combine(filterQuery) { list, query ->
      if (query.isBlank()) {
        list
      } else {
        list.filter { item ->
          !item.displayName.isNullOrBlank() &&
            item.displayName.contains(query, ignoreCase = true)
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
        SortMode.NAME -> list.sortedBy { it.displayName ?: "" }
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

  override fun onCleared() {
    scanner.stopScan()
    super.onCleared()
  }
}
