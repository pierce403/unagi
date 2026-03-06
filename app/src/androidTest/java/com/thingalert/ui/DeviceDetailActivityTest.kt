package com.thingalert.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceDetailActivityTest {
  @Test
  fun launchesDeviceDetailActivity() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val intent = DeviceDetailActivity.intent(context, "test-key")
    ActivityScenario.launch<DeviceDetailActivity>(intent).use { scenario ->
      // Activity should launch even if device is not in database.
    }
  }
}
