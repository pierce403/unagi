package ninja.unagi.util

import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {
  @Test
  fun formatTimestamp_usesStableLocalizedRendering() {
    val originalLocale = Locale.getDefault()
    val originalTimeZone = TimeZone.getDefault()
    Locale.setDefault(Locale.US)
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    try {
      val formatted = Formatters.formatTimestamp(1767323045000L).replace(Regex("\\p{Zs}+"), " ")
      assertEquals("Jan 2, 2026, 3:04:05 AM", formatted)
    } finally {
      Locale.setDefault(originalLocale)
      TimeZone.setDefault(originalTimeZone)
    }
  }

  @Test
  fun formatTimestamp_isConsistentAcrossConcurrentCalls() {
    val originalLocale = Locale.getDefault()
    val originalTimeZone = TimeZone.getDefault()
    Locale.setDefault(Locale.US)
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    val executor = Executors.newFixedThreadPool(8)
    try {
      val expected = Formatters.formatTimestamp(1767323045000L)
      val tasks = List(512) {
        Callable { Formatters.formatTimestamp(1767323045000L) }
      }
      val results = executor.invokeAll(tasks)
        .map { it.get(5, TimeUnit.SECONDS) }
        .toSet()
      assertEquals(setOf(expected), results)
    } finally {
      executor.shutdownNow()
      Locale.setDefault(originalLocale)
      TimeZone.setDefault(originalTimeZone)
    }
  }
}
