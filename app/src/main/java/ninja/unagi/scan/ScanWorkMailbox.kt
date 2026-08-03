package ninja.unagi.scan

import java.util.LinkedHashMap
import kotlinx.coroutines.channels.Channel

/**
 * A bounded mailbox for high-volume scan reports plus low-volume lifecycle controls.
 *
 * Reports with the same non-null key replace the older queued report as one whole value.
 * Fields from separate reports are never combined. A distinct report is dropped when the
 * report capacity is exhausted; controls are always retained so stop/failure barriers remain
 * ordered with the reports accepted before them.
 */
internal class ScanWorkMailbox<T>(
  private val reportCapacity: Int,
  private val isControl: (T) -> Boolean,
  private val reportKey: (T) -> Any?
) {
  init {
    require(reportCapacity > 0) { "reportCapacity must be positive" }
  }

  private data class Entry<T>(
    val value: T,
    val control: Boolean,
    val key: Any?
  )

  private val lock = Any()
  private val entries = LinkedHashMap<Long, Entry<T>>()
  private val reportSequenceByKey = mutableMapOf<Any, Long>()
  private val wakeUp = Channel<Unit>(Channel.CONFLATED)
  private var nextSequence = 0L
  private var reportCount = 0

  fun offer(value: T): ScanMailboxOffer {
    val result = synchronized(lock) {
      val control = isControl(value)
      val key = if (control) null else reportKey(value)

      if (control) {
        append(value = value, control = true, key = null)
        ScanMailboxOffer(
          status = ScanMailboxOfferStatus.ACCEPTED,
          reportDepth = reportCount
        )
      } else {
        val priorSequence = key?.let(reportSequenceByKey::get)
        when {
          priorSequence != null -> {
            // Replacing by an existing LinkedHashMap key preserves insertion order, so a
            // report accepted before a lifecycle control can never cross that barrier.
            entries[priorSequence] = Entry(value = value, control = false, key = key)
            ScanMailboxOffer(
              status = ScanMailboxOfferStatus.COALESCED,
              reportDepth = reportCount
            )
          }

          reportCount < reportCapacity -> {
            append(value = value, control = false, key = key)
            reportCount += 1
            ScanMailboxOffer(
              status = ScanMailboxOfferStatus.ACCEPTED,
              reportDepth = reportCount
            )
          }

          else -> ScanMailboxOffer(
            status = ScanMailboxOfferStatus.DROPPED,
            reportDepth = reportCount
          )
        }
      }
    }

    if (result.status != ScanMailboxOfferStatus.DROPPED) {
      wakeUp.trySend(Unit)
    }
    return result
  }

  suspend fun receive(): T {
    while (true) {
      poll()?.let { return it }
      wakeUp.receive()
    }
  }

  internal fun poll(): T? {
    return synchronized(lock) {
      val iterator = entries.entries.iterator()
      if (!iterator.hasNext()) {
        null
      } else {
        val (_, entry) = iterator.next()
        iterator.remove()
        if (!entry.control) {
          reportCount -= 1
          entry.key?.let { key ->
            reportSequenceByKey.remove(key)
          }
        }
        entry.value
      }
    }
  }

  fun reportDepth(): Int = synchronized(lock) { reportCount }

  private fun append(value: T, control: Boolean, key: Any?) {
    val sequence = nextSequence++
    entries[sequence] = Entry(value = value, control = control, key = key)
    if (!control && key != null) {
      reportSequenceByKey[key] = sequence
    }
  }
}

internal data class ScanMailboxOffer(
  val status: ScanMailboxOfferStatus,
  val reportDepth: Int
)

internal enum class ScanMailboxOfferStatus {
  ACCEPTED,
  COALESCED,
  DROPPED
}
