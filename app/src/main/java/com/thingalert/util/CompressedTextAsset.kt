package com.thingalert.util

import android.content.Context
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

object CompressedTextAsset {
  private const val GZIP_MAGIC_FIRST = 0x1F
  private const val GZIP_MAGIC_SECOND = 0x8B

  fun openFirstAvailable(context: Context, assetNames: List<String>): InputStream? {
    val assets = context.applicationContext.assets
    for (assetName in assetNames) {
      try {
        return assets.open(assetName)
      } catch (_: java.io.FileNotFoundException) {
        // Try next candidate
      }
    }
    return null
  }

  fun prepareInputStream(inputStream: InputStream): InputStream {
    val buffered = if (inputStream is BufferedInputStream) {
      inputStream
    } else {
      BufferedInputStream(inputStream)
    }
    buffered.mark(2)
    val first = buffered.read()
    val second = buffered.read()
    buffered.reset()
    val isGzip = first == GZIP_MAGIC_FIRST && second == GZIP_MAGIC_SECOND
    return if (isGzip) {
      GZIPInputStream(buffered)
    } else {
      buffered
    }
  }
}
