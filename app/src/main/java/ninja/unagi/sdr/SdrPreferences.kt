package ninja.unagi.sdr

import android.content.Context

object SdrPreferences {
  private const val PREFS_NAME = "unagi_sdr"
  private const val KEY_ENABLED = "sdr_enabled"
  private const val KEY_SOURCE = "sdr_source"
  private const val KEY_FREQUENCY = "sdr_frequency"
  private const val KEY_GAIN = "sdr_gain"
  private const val KEY_NETWORK_HOST = "sdr_network_host"
  private const val KEY_NETWORK_PORT = "sdr_network_port"

  private fun prefs(context: Context) =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  fun isEnabled(context: Context): Boolean =
    prefs(context).getBoolean(KEY_ENABLED, false)

  fun setEnabled(context: Context, enabled: Boolean) {
    prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
  }

  fun source(context: Context): SdrSource =
    SdrSource.fromValue(prefs(context).getString(KEY_SOURCE, null))

  fun setSource(context: Context, source: SdrSource) {
    prefs(context).edit().putString(KEY_SOURCE, source.value).apply()
  }

  fun frequencyMhz(context: Context): Int =
    prefs(context).getInt(KEY_FREQUENCY, 433)

  fun setFrequencyMhz(context: Context, mhz: Int) {
    prefs(context).edit().putInt(KEY_FREQUENCY, mhz).apply()
  }

  fun frequencyHz(context: Context): Int = when (frequencyMhz(context)) {
    315 -> 315_000_000
    else -> 433_920_000
  }

  fun gain(context: Context): Int? {
    val value = prefs(context).getInt(KEY_GAIN, -1)
    return if (value < 0) null else value
  }

  fun setGain(context: Context, gain: Int?) {
    prefs(context).edit().putInt(KEY_GAIN, gain ?: -1).apply()
  }

  fun networkHost(context: Context): String =
    prefs(context).getString(KEY_NETWORK_HOST, "192.168.1.100") ?: "192.168.1.100"

  fun setNetworkHost(context: Context, host: String) {
    prefs(context).edit().putString(KEY_NETWORK_HOST, host).apply()
  }

  fun networkPort(context: Context): Int =
    prefs(context).getInt(KEY_NETWORK_PORT, 1234)

  fun setNetworkPort(context: Context, port: Int) {
    prefs(context).edit().putInt(KEY_NETWORK_PORT, port).apply()
  }

  enum class SdrSource(val value: String) {
    USB("usb"),
    NETWORK("network");

    companion object {
      fun fromValue(value: String?): SdrSource =
        entries.firstOrNull { it.value == value } ?: USB
    }
  }
}
