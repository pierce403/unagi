package ninja.unagi.sdr

sealed class SdrState {
  data object Idle : SdrState()
  data object Scanning : SdrState()
  data object UsbNotConnected : SdrState()
  data object UsbPermissionDenied : SdrState()
  data class Error(val message: String) : SdrState()
}
