package com.thingalert.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque

object DebugLog {
  private const val MAX_LINES = 400
  private const val TAG = "unagi"
  private val buffer = ArrayDeque<String>(MAX_LINES)
  private val _entries = MutableStateFlow<List<String>>(emptyList())

  val entries: StateFlow<List<String>> = _entries

  fun log(message: String, level: Int = Log.DEBUG, throwable: Throwable? = null) {
    val line = "${Formatters.formatTimestamp(System.currentTimeMillis())} $message"

    synchronized(buffer) {
      if (buffer.size >= MAX_LINES) {
        buffer.removeFirst()
      }
      buffer.addLast(line)
      _entries.value = buffer.toList()
    }

    when (level) {
      Log.ERROR -> Log.e(TAG, message, throwable)
      Log.WARN -> Log.w(TAG, message, throwable)
      Log.INFO -> Log.i(TAG, message, throwable)
      else -> Log.d(TAG, message, throwable)
    }
  }
}
