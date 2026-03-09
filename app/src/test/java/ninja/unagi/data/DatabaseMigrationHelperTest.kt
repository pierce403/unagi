package ninja.unagi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseMigrationHelperTest {
  @Test
  fun `attach statement uses raw hex literal without extra quotes`() {
    val statement = DatabaseMigrationHelper.buildAttachStatement(
      databasePath = "/tmp/thingalert.db",
      passphrase = byteArrayOf(0x01, 0x23, 0x45, 0x67)
    )

    assertEquals(
      "ATTACH DATABASE '/tmp/thingalert.db' AS encrypted KEY x'01234567'",
      statement
    )
  }

  @Test
  fun `attach statement escapes single quotes in file paths`() {
    val statement = DatabaseMigrationHelper.buildAttachStatement(
      databasePath = "/tmp/it's.db",
      passphrase = byteArrayOf(0x0A, 0x0B)
    )

    assertEquals(
      "ATTACH DATABASE '/tmp/it''s.db' AS encrypted KEY x'0a0b'",
      statement
    )
  }

  @Test
  fun `plaintext header detection matches sqlite magic bytes`() {
    assertTrue(
      DatabaseMigrationHelper.isPlaintextHeader("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))
    )
    assertFalse(
      DatabaseMigrationHelper.isPlaintextHeader(byteArrayOf(0x01, 0x02, 0x03, 0x04))
    )
  }
}
