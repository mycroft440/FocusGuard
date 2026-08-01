package com.focusguard.admin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end policy readback for a test device provisioned with this build as Device Owner.
 * The test is skipped on ordinary CI emulators because Android only grants Device Owner
 * during device provisioning.
 */
@RunWith(AndroidJUnit4::class)
class DeviceOwnerProtectionInstrumentedTest {

    @Test
    fun provisionedDeviceConfirmsEveryRequiredSelfProtectionPolicy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = DeviceOwnerManager.getInstance(context)

        assumeTrue("Test device is not provisioned as Device Owner", manager.isDeviceOwnerActive())
        assumeFalse("Authenticated maintenance is intentionally relaxed", manager.isMaintenanceActive())

        manager.applyNuclearShield()
        val diagnostics = DeviceOwnerProtectionAuditor(context).inspect()

        assertTrue(
            "Android did not confirm all required Device Owner policies: $diagnostics",
            diagnostics.isFullyProtected
        )
    }
}
