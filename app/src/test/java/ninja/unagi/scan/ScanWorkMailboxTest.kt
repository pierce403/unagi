package ninja.unagi.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScanWorkMailboxTest {
  private sealed interface Work {
    data class Report(
      val key: String?,
      val manufacturerIds: Set<Int>,
      val serviceUuids: Set<Int>,
      val sequence: Int
    ) : Work

    data class Control(val label: String) : Work
  }

  @Test
  fun `coalescing replaces an exact-key report as a whole value`() {
    val mailbox = mailbox(capacity = 2)
    val first = Work.Report(
      key = "same-raw-report",
      manufacturerIds = setOf(0x01AB),
      serviceUuids = setOf(0xFD5F),
      sequence = 1
    )
    val replacement = Work.Report(
      key = "same-raw-report",
      manufacturerIds = setOf(0x01AB),
      serviceUuids = setOf(0xFD5F),
      sequence = 2
    )

    assertEquals(ScanMailboxOfferStatus.ACCEPTED, mailbox.offer(first).status)
    assertEquals(ScanMailboxOfferStatus.COALESCED, mailbox.offer(replacement).status)

    assertEquals(replacement, mailbox.poll())
    assertNull(mailbox.poll())
  }

  @Test
  fun `different reports are never field-merged`() {
    val mailbox = mailbox(capacity = 2)
    val companyOnly = Work.Report(
      key = "company-only",
      manufacturerIds = setOf(0x01AB),
      serviceUuids = emptySet(),
      sequence = 1
    )
    val serviceOnly = Work.Report(
      key = "service-only",
      manufacturerIds = emptySet(),
      serviceUuids = setOf(0xFD5F),
      sequence = 2
    )

    mailbox.offer(companyOnly)
    mailbox.offer(serviceOnly)

    assertEquals(companyOnly, mailbox.poll())
    assertEquals(serviceOnly, mailbox.poll())
    assertNull(mailbox.poll())
  }

  @Test
  fun `full mailbox drops a distinct report but retains ordered controls`() {
    val mailbox = mailbox(capacity = 1)
    val accepted = Work.Report("report-1", emptySet(), emptySet(), 1)
    val dropped = Work.Report("report-2", emptySet(), emptySet(), 2)
    val stop = Work.Control("stop")

    assertEquals(ScanMailboxOfferStatus.ACCEPTED, mailbox.offer(accepted).status)
    assertEquals(ScanMailboxOfferStatus.DROPPED, mailbox.offer(dropped).status)
    assertEquals(ScanMailboxOfferStatus.ACCEPTED, mailbox.offer(stop).status)

    assertEquals(accepted, mailbox.poll())
    assertEquals(stop, mailbox.poll())
    assertNull(mailbox.poll())
  }

  @Test
  fun `coalescing in place cannot move a report across a stop barrier`() {
    val mailbox = mailbox(capacity = 2)
    val first = Work.Report("same", emptySet(), emptySet(), 1)
    val stop = Work.Control("stop")
    val replacement = first.copy(sequence = 2)

    mailbox.offer(first)
    mailbox.offer(stop)
    mailbox.offer(replacement)

    assertEquals(replacement, mailbox.poll())
    assertEquals(stop, mailbox.poll())
  }

  @Test
  fun `unpaired Meta report cannot replace a queued paired report`() {
    val mailbox = mailbox(capacity = 1)
    val paired = Work.Report(
      key = "raw:01ab+fd5f",
      manufacturerIds = setOf(0x01AB),
      serviceUuids = setOf(0xFD5F),
      sequence = 1
    )
    val companyOnly = Work.Report(
      key = "raw:01ab-only",
      manufacturerIds = setOf(0x01AB),
      serviceUuids = emptySet(),
      sequence = 2
    )

    mailbox.offer(paired)
    assertEquals(ScanMailboxOfferStatus.DROPPED, mailbox.offer(companyOnly).status)

    assertEquals(paired, mailbox.poll())
    assertNull(mailbox.poll())
  }

  private fun mailbox(capacity: Int): ScanWorkMailbox<Work> {
    return ScanWorkMailbox(
      reportCapacity = capacity,
      isControl = { it is Work.Control },
      reportKey = { work -> (work as? Work.Report)?.key }
    )
  }
}
