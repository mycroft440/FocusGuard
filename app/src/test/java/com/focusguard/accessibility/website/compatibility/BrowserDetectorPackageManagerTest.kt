package com.focusguard.accessibility.website.compatibility

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

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

    @Test
    fun `unknown result is retried after short cache deadline`() {
        registerHandler(BrowserDetector.HTTPS_PROBE)
        assertThat(BrowserDetector.detect(packageName).classification)
            .isEqualTo(BrowserClassification.UNKNOWN)

        registerHandler(BrowserDetector.HTTP_PROBE)
        val cached = BrowserDetector.detect(packageName)
        assertThat(cached.classification).isEqualTo(BrowserClassification.UNKNOWN)
        assertThat(cached.fromCache).isTrue()

        ShadowSystemClock.advanceBy(251L, TimeUnit.MILLISECONDS)
        val retried = BrowserDetector.detect(packageName)

        assertThat(retried.classification).isEqualTo(BrowserClassification.CONFIRMED_BROWSER)
        assertThat(retried.fromCache).isFalse()
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
