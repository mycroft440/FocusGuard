package com.focusguard.accessibility.website.compatibility

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Looper
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
        installCandidatePackage()
        BrowserDetector.initialize(context)
    }

    @Test
    fun `six https probes with common broad activity and browser category confirm browser`() {
        registerAllHttpsHandlers(activityName = "$packageName.BrowserActivity", broad = true)
        registerBrowserCategory(activityName = "$packageName.BrowserActivity")

        val decision = BrowserDetector.detect(packageName)

        assertThat(decision.classification).isEqualTo(BrowserClassification.CONFIRMED_BROWSER)
        assertThat(decision.reason)
            .isEqualTo(BrowserDetectionReason.STRUCTURAL_BROWSER_CONFIRMED)
    }

    @Test
    fun `https common activity without broad filters stays probable`() {
        registerAllHttpsHandlers(activityName = "$packageName.BrowserActivity", broad = false)
        registerBrowserCategory(activityName = "$packageName.BrowserActivity")

        val decision = BrowserDetector.detect(packageName)

        assertThat(decision.classification).isEqualTo(BrowserClassification.PROBABLE_BROWSER)
        assertThat(decision.classification.isBrowserLike).isFalse()
    }

    @Test
    fun `duplicate narrow filter after broad filter does not make B order dependent`() {
        BrowserDetector.HTTPS_PROBES.forEach { url ->
            registerHandler(url, "$packageName.BrowserActivity", broad = true)
            registerHandler(url, "$packageName.BrowserActivity", broad = false)
        }
        registerBrowserCategory(activityName = "$packageName.BrowserActivity")

        val decision = BrowserDetector.detect(packageName)

        assertThat(decision.classification).isEqualTo(BrowserClassification.CONFIRMED_BROWSER)
        assertThat(decision.reason)
            .isEqualTo(BrowserDetectionReason.STRUCTURAL_BROWSER_CONFIRMED)
    }

    @Test
    fun `distributed specialized activities do not satisfy common https activity`() {
        BrowserDetector.HTTPS_PROBES.forEachIndexed { index, url ->
            registerHandler(
                url = url,
                activityName = "$packageName.Specialized$index",
                broad = true
            )
        }
        registerBrowserCategory(activityName = "$packageName.Specialized0")

        val decision = BrowserDetector.detect(packageName)

        assertThat(decision.classification).isEqualTo(BrowserClassification.NOT_BROWSER)
    }

    @Test
    fun `known profile is resolved without package manager installation`() {
        BrowserDetector.initialize(context)

        val decision = BrowserDetector.detect("com.android.chrome")

        assertThat(decision.classification).isEqualTo(BrowserClassification.CONFIRMED_BROWSER)
        assertThat(decision.reason).isEqualTo(BrowserDetectionReason.KNOWN_BROWSER_PROFILE)
    }

    @Test
    fun `probable cache expires and structural evidence is re-evaluated`() {
        registerAllHttpsHandlers(activityName = "$packageName.BrowserActivity", broad = true)
        assertThat(BrowserDetector.detect(packageName).classification)
            .isEqualTo(BrowserClassification.PROBABLE_BROWSER)

        registerBrowserCategory(activityName = "$packageName.BrowserActivity")
        val cached = BrowserDetector.detect(packageName)
        assertThat(cached.classification).isEqualTo(BrowserClassification.PROBABLE_BROWSER)
        assertThat(cached.fromCache).isTrue()

        ShadowSystemClock.advanceBy(5_001L, TimeUnit.MILLISECONDS)
        val retried = BrowserDetector.detect(packageName)

        assertThat(retried.classification).isEqualTo(BrowserClassification.CONFIRMED_BROWSER)
        assertThat(retried.fromCache).isFalse()
    }

    @Test
    fun `package changed broadcast invalidates cached classification immediately`() {
        registerAllHttpsHandlers(activityName = "$packageName.BrowserActivity", broad = true)
        registerBrowserCategory(activityName = "$packageName.BrowserActivity")

        assertThat(BrowserDetector.detect(packageName).classification)
            .isEqualTo(BrowserClassification.CONFIRMED_BROWSER)
        assertThat(BrowserDetector.detect(packageName).fromCache).isTrue()

        context.sendBroadcast(
            Intent(Intent.ACTION_PACKAGE_CHANGED, Uri.parse("package:$packageName"))
        )
        shadowOf(Looper.getMainLooper()).idle()

        val retried = BrowserDetector.detect(packageName)
        assertThat(retried.classification).isEqualTo(BrowserClassification.CONFIRMED_BROWSER)
        assertThat(retried.fromCache).isFalse()
    }

    @Suppress("DEPRECATION")
    private fun installCandidatePackage() {
        val applicationInfo = ApplicationInfo().apply {
            packageName = this@BrowserDetectorPackageManagerTest.packageName
            enabled = true
        }
        val packageInfo = PackageInfo().apply {
            packageName = this@BrowserDetectorPackageManagerTest.packageName
            versionCode = 1
            firstInstallTime = 5L
            lastUpdateTime = 10L
            this.applicationInfo = applicationInfo
        }
        shadowOf(context.packageManager).installPackage(packageInfo)
    }

    private fun registerAllHttpsHandlers(activityName: String, broad: Boolean) {
        BrowserDetector.HTTPS_PROBES.forEach { url ->
            registerHandler(url, activityName, broad)
        }
    }

    @Suppress("DEPRECATION")
    private fun registerHandler(url: String, activityName: String, broad: Boolean) {
        val activityInfo = usableActivity(activityName)
        val filter = IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addDataScheme(Uri.parse(url).scheme)
            if (!broad) {
                addDataAuthority(Uri.parse(url).host, null)
            }
        }
        val resolveInfo = ResolveInfo().apply {
            this.activityInfo = activityInfo
            this.filter = filter
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(probeIntent(url), resolveInfo)
    }

    @Suppress("DEPRECATION")
    private fun registerBrowserCategory(activityName: String) {
        val resolveInfo = ResolveInfo().apply {
            activityInfo = usableActivity(activityName)
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_BROWSER)
                setPackage(packageName)
            },
            resolveInfo
        )
    }

    private fun usableActivity(activityName: String): ActivityInfo = ActivityInfo().apply {
        packageName = this@BrowserDetectorPackageManagerTest.packageName
        name = activityName
        enabled = true
        exported = true
        applicationInfo = ApplicationInfo().apply {
            packageName = this@BrowserDetectorPackageManagerTest.packageName
            enabled = true
        }
    }

    private fun probeIntent(url: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        setPackage(packageName)
    }
}
