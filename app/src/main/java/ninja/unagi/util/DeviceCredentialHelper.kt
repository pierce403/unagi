package ninja.unagi.util

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Helper to gate sensitive operations behind device credential (PIN/pattern/password/biometric).
 * Falls back gracefully if no lock screen is configured.
 */
object DeviceCredentialHelper {

  private const val ALLOWED_AUTHENTICATORS =
    BiometricManager.Authenticators.BIOMETRIC_STRONG or
      BiometricManager.Authenticators.DEVICE_CREDENTIAL

  fun isDeviceSecure(activity: FragmentActivity): Boolean {
    val result = BiometricManager.from(activity).canAuthenticate(ALLOWED_AUTHENTICATORS)
    return result == BiometricManager.BIOMETRIC_SUCCESS
  }

  /**
   * Prompt user for device credential. Calls [onSuccess] if authenticated,
   * [onFailure] if denied or error. If no lock screen is set, calls [onSuccess] immediately.
   */
  fun authenticate(
    activity: FragmentActivity,
    title: String,
    subtitle: String,
    onSuccess: () -> Unit,
    onFailure: (String?) -> Unit = {}
  ) {
    if (!isDeviceSecure(activity)) {
      onSuccess()
      return
    }

    val executor = ContextCompat.getMainExecutor(activity)
    val callback = object : BiometricPrompt.AuthenticationCallback() {
      override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
        onSuccess()
      }

      override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
        onFailure(errString.toString())
      }

      override fun onAuthenticationFailed() {
        // Individual attempt failed — BiometricPrompt will retry automatically
      }
    }

    val prompt = BiometricPrompt(activity, executor, callback)
    val info = BiometricPrompt.PromptInfo.Builder()
      .setTitle(title)
      .setSubtitle(subtitle)
      .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
      .build()
    prompt.authenticate(info)
  }
}
