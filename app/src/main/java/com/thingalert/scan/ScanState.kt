package com.thingalert.scan

sealed class ScanState {
  data object Idle : ScanState()
  data object Scanning : ScanState()
  data object BluetoothOff : ScanState()
  data object LocationServicesOff : ScanState()
  data object Unsupported : ScanState()
  data object MissingPermission : ScanState()
  data class Error(val message: String) : ScanState()
}
