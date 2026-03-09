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

  fun migrateIfNeeded(context: Context, passphrase: ByteArray) {
    val dbFile = context.getDatabasePath(DB_NAME)
    if (!dbFile.exists()) return

    if (!isPlaintext(dbFile)) return

    Log.i(TAG, "Plaintext database detected, migrating to encrypted")
    System.loadLibrary("sqlcipher")

    val tempFile = File(dbFile.parentFile, "${DB_NAME}_encrypted")
    try {
      val db = SQLiteDatabase.openDatabase(
        dbFile.absolutePath, "", null, SQLiteDatabase.OPEN_READWRITE, null, null
      )

      db.rawExecSQL("ATTACH DATABASE '${tempFile.absolutePath}' AS encrypted KEY '${passphraseToHex(passphrase)}'")
      db.rawExecSQL("SELECT sqlcipher_export('encrypted')")
      db.rawExecSQL("DETACH DATABASE encrypted")
      db.close()

      // P5: overwrite old plaintext file with random bytes before deleting
      secureDelete(dbFile)
      secureDelete(File(dbFile.absolutePath + "-wal"))
      secureDelete(File(dbFile.absolutePath + "-shm"))
      secureDelete(File(dbFile.absolutePath + "-journal"))

      tempFile.renameTo(dbFile)
      Log.i(TAG, "Migration to encrypted database complete")
    } catch (e: Exception) {
      Log.e(TAG, "Migration failed, deleting temp file", e)
      tempFile.delete()
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

  private fun passphraseToHex(passphrase: ByteArray): String {
    return "x'" + passphrase.joinToString("") { "%02x".format(it) } + "'"
  }
}
