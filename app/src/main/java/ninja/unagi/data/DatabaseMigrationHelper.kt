package ninja.unagi.data

import android.content.Context
import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.security.SecureRandom

/**
 * One-time migration from plaintext Room database to SQLCipher-encrypted database.
 *
 * Checks if the existing DB file is plaintext (by trying to open without passphrase).
 * If so, uses sqlcipher_export() to create an encrypted copy, then swaps files.
 * Per pitfall P5: overwrites the old plaintext file with random bytes before deleting.
 */
object DatabaseMigrationHelper {
  private const val TAG = "DbMigration"
  private const val DB_NAME = "thingalert.db"

  enum class OpenMode {
    ENCRYPTED,
    PLAINTEXT_FALLBACK
  }

  fun prepareOpenMode(context: Context, passphrase: ByteArray): OpenMode {
    val dbFile = context.getDatabasePath(DB_NAME)
    if (!dbFile.exists()) return OpenMode.ENCRYPTED

    loadSqlCipher()

    if (canOpenEncrypted(dbFile, passphrase)) {
      return OpenMode.ENCRYPTED
    }

    if (!isPlaintext(dbFile) && !canOpenPlaintext(dbFile)) {
      Log.w(TAG, "Database is not plaintext and could not be opened with the current SQLCipher key")
      return OpenMode.ENCRYPTED
    }

    return if (migratePlaintextDatabase(dbFile, passphrase)) {
      OpenMode.ENCRYPTED
    } else {
      Log.e(TAG, "Falling back to plaintext Room open after SQLCipher migration failure")
      OpenMode.PLAINTEXT_FALLBACK
    }
  }

  private fun migratePlaintextDatabase(dbFile: File, passphrase: ByteArray): Boolean {
    Log.i(TAG, "Plaintext database detected, migrating to encrypted")
    val tempFile = File(dbFile.parentFile, "${DB_NAME}_encrypted")
    val backupFile = File(dbFile.parentFile, "${DB_NAME}_plaintext_backup")
    try {
      tempFile.delete()
      backupFile.delete()

      val db = SQLiteDatabase.openDatabase(
        dbFile.absolutePath,
        null,
        SQLiteDatabase.OPEN_READWRITE
      )

      db.rawExecSQL(buildAttachStatement(tempFile.absolutePath, passphrase))
      db.rawExecSQL("SELECT sqlcipher_export('encrypted')")
      db.rawExecSQL("DETACH DATABASE encrypted")
      db.close()

      if (!canOpenEncrypted(tempFile, passphrase)) {
        error("Encrypted migration output could not be reopened with the generated passphrase")
      }

      val walFile = File(dbFile.absolutePath + "-wal")
      val shmFile = File(dbFile.absolutePath + "-shm")
      val journalFile = File(dbFile.absolutePath + "-journal")

      if (!dbFile.renameTo(backupFile)) {
        error("Could not move plaintext database aside before finalizing migration")
      }
      if (!tempFile.renameTo(dbFile)) {
        backupFile.renameTo(dbFile)
        error("Could not move encrypted database into place")
      }

      secureDelete(backupFile)
      secureDelete(walFile)
      secureDelete(shmFile)
      secureDelete(journalFile)
      Log.i(TAG, "Migration to encrypted database complete")
      return true
    } catch (e: Exception) {
      Log.e(TAG, "Migration failed, deleting temp file", e)
      tempFile.delete()
      return false
    }
  }

  private fun isPlaintext(dbFile: File): Boolean {
    return try {
      val header = ByteArray(16)
      dbFile.inputStream().use { it.read(header) }
      // SQLite plaintext files start with "SQLite format 3\000"
      String(header, Charsets.US_ASCII).startsWith("SQLite format 3")
    } catch (e: Exception) {
      false
    }
  }

  internal fun isPlaintextHeader(header: ByteArray): Boolean {
    return String(header, Charsets.US_ASCII).startsWith("SQLite format 3")
  }

  internal fun buildAttachStatement(databasePath: String, passphrase: ByteArray): String {
    return "ATTACH DATABASE '${escapeSqlString(databasePath)}' AS encrypted KEY ${passphraseToHexLiteral(passphrase)}"
  }

  private fun canOpenPlaintext(dbFile: File): Boolean {
    return runCatching {
      val db = SQLiteDatabase.openDatabase(
        dbFile.absolutePath,
        null,
        SQLiteDatabase.OPEN_READONLY
      )
      try {
        db.query("SELECT COUNT(*) FROM sqlite_master").use { cursor ->
          cursor.moveToFirst()
        }
      } finally {
        db.close()
      }
    }.isSuccess
  }

  private fun canOpenEncrypted(dbFile: File, passphrase: ByteArray): Boolean {
    return runCatching {
      val db = SQLiteDatabase.openDatabase(
        dbFile.absolutePath,
        passphrase,
        null,
        SQLiteDatabase.OPEN_READONLY,
        null,
        null
      )
      try {
        db.query("SELECT COUNT(*) FROM sqlite_master").use { cursor ->
          cursor.moveToFirst()
        }
      } finally {
        db.close()
      }
    }.isSuccess
  }

  private fun loadSqlCipher() {
    System.loadLibrary("sqlcipher")
  }

  private fun secureDelete(file: File) {
    if (!file.exists()) return
    try {
      val length = file.length()
      val random = SecureRandom()
      val buffer = ByteArray(4096)
      file.outputStream().use { out ->
        var remaining = length
        while (remaining > 0) {
          random.nextBytes(buffer)
          val toWrite = minOf(buffer.size.toLong(), remaining).toInt()
          out.write(buffer, 0, toWrite)
          remaining -= toWrite
        }
      }
    } catch (_: Exception) {
      // Best effort
    }
    file.delete()
  }

  private fun passphraseToHexLiteral(passphrase: ByteArray): String {
    return "x'" + passphrase.joinToString("") { "%02x".format(it) } + "'"
  }

  private fun escapeSqlString(value: String): String {
    return value.replace("'", "''")
  }
}
