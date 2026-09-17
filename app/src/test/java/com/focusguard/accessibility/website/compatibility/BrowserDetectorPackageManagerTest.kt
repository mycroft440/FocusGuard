package com.focusguard.accessibility.website.compatibility

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BrowserDetectorPackageManagerTest {
    private val packageName = "test.fake.browser"
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        BrowserDetector.initialize(context)
    }

    @Test
    fun `package manager handlers for generic http and https confirm browser`() {
        registerHandler(BrowserDetector.HTTP_PROBE)
        registerHandler(BrowserDetector.HTTPS_PROBE)

        val decision = BrowserDetector.detect(packageName)

        assertThat(decision.classification).isEqualTo(BrowserClassification.CONFIRMED_BROWSER)
        assertThat(decision.reason).isEqualTo(BrowserDetectionReason.HTTP_HTTPS_CONFIRMED)
    }

    @Test
    fun `single generic scheme stays inconclusive in real package manager query`() {
        registerHandler(BrowserDetector.HTTPS_PROBE)

        val decision = BrowserDetector.detect(packageName)

        assertThat(decision.classification).isEqualTo(BrowserClassification.UNKNOWN)
        assertThat(decision.reason).isEqualTo(BrowserDetectionReason.PARTIAL_GENERIC_HANDLER)
    }

    @Suppress("DEPRECATION")
    private fun registerHandler(url: String) {
        val activityInfo = ActivityInfo().apply {
            packageName = this@BrowserDetectorPackageManagerTest.packageName
            name = "$packageName.BrowserActivity"
            applicationInfo = ApplicationInfo().apply {
                packageName = this@BrowserDetectorPackageManagerTest.packageName
            }
        }
        val resolveInfo = ResolveInfo().apply {
            this.activityInfo = activityInfo
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(probeIntent(url), resolveInfo)
    }

    private fun probeIntent(url: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        setPackage(packageName)
    }
}
