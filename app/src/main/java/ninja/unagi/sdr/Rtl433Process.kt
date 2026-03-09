package ninja.unagi.sdr

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ninja.unagi.util.DebugLog
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class Rtl433Process(private val context: Context) {
  private var process: Process? = null
  private var readerJob: Job? = null
  private var errorReaderJob: Job? = null

  fun start(
    scope: CoroutineScope,
    frequencyHz: Int,
    gain: Int?,
    onReading: (TpmsReading) -> Unit,
    onError: (String) -> Unit
  ) {
    stop()
    val binary = File(context.applicationInfo.nativeLibraryDir, "librtl_433.so")
    if (!binary.exists()) {
      DebugLog.log("rtl_433 binary not found at ${binary.absolutePath}", level = android.util.Log.ERROR)
      onError("rtl_433 binary not found. NDK build required.")
      return
    }

    val args = buildList {
      add(binary.absolutePath)
      add("-f"); add(frequencyHz.toString())
      add("-F"); add("json")
      add("-M"); add("level")
      gain?.let { add("-g"); add(it.toString()) }
    }

    try {
      DebugLog.log("Starting rtl_433: ${args.joinToString(" ")}")
      val proc = ProcessBuilder(args)
        .redirectErrorStream(false)
        .start()
      process = proc

      readerJob = scope.launch(Dispatchers.IO) {
        try {
          BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
            while (isActive) {
              val line = reader.readLine() ?: break
              val reading = Rtl433JsonParser.parse(line)
              if (reading != null) {
                withContext(Dispatchers.Main) { onReading(reading) }
              }
            }
          }
        } catch (e: Exception) {
          if (isActive) {
            DebugLog.log("rtl_433 stdout read error: ${e.message}", level = android.util.Log.ERROR)
          }
        }
      }

      errorReaderJob = scope.launch(Dispatchers.IO) {
        try {
          BufferedReader(InputStreamReader(proc.errorStream)).use { reader ->
            while (isActive) {
              val line = reader.readLine() ?: break
              DebugLog.log("rtl_433 stderr: $line")
            }
          }
        } catch (_: Exception) {
          // Ignore stderr read errors
        }
      }
    } catch (e: Exception) {
      DebugLog.log("Failed to start rtl_433: ${e.message}", level = android.util.Log.ERROR)
      onError("Failed to start rtl_433: ${e.message}")
    }
  }

  fun stop() {
    readerJob?.cancel()
    readerJob = null
    errorReaderJob?.cancel()
    errorReaderJob = null
    process?.destroy()
    process = null
  }

  val isRunning: Boolean
    get() {
      val proc = process ?: return false
      return try {
        proc.exitValue()
        false // Process has exited
      } catch (_: IllegalThreadStateException) {
        true // Process is still running
      }
    }
}
