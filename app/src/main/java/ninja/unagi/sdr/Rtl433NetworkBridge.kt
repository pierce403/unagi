package ninja.unagi.sdr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ninja.unagi.util.DebugLog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket

class Rtl433NetworkBridge {
  private var connectionJob: Job? = null
  private var socket: Socket? = null

  fun connect(
    scope: CoroutineScope,
    host: String,
    port: Int,
    onReading: (TpmsReading) -> Unit,
    onError: (String) -> Unit
  ) {
    disconnect()
    connectionJob = scope.launch(Dispatchers.IO) {
      try {
        DebugLog.log("SDR network bridge connecting to $host:$port")
        val s = Socket(host, port)
        socket = s
        DebugLog.log("SDR network bridge connected")
        BufferedReader(InputStreamReader(s.getInputStream())).use { reader ->
          while (isActive && !s.isClosed) {
            val line = reader.readLine() ?: break
            val reading = Rtl433JsonParser.parse(line)
            if (reading != null) {
              withContext(Dispatchers.Main) { onReading(reading) }
            }
          }
        }
        DebugLog.log("SDR network bridge stream ended")
      } catch (e: Exception) {
        if (isActive) {
          DebugLog.log("SDR network bridge error: ${e.message}", level = android.util.Log.ERROR)
          withContext(Dispatchers.Main) { onError(e.message ?: "Network bridge connection failed") }
        }
      } finally {
        socket?.close()
        socket = null
      }
    }
  }

  fun disconnect() {
    connectionJob?.cancel()
    connectionJob = null
    socket?.close()
    socket = null
  }

  val isConnected: Boolean
    get() = socket?.isConnected == true && socket?.isClosed == false
}
