package com.focusguard.service

import android.content.Context
import android.content.Intent
import com.focusguard.ui.BlockNoticeActivity
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebsiteBlockNavigationTest {

    private val context: Context = RuntimeEnvironment.getApplication().applicationContext

    @Test
    fun `notice has a visible interval before the browser redirect`() {
        assertThat(BlockingAccessibilityService.WEBSITE_BLOCK_NOTICE_DURATION_MILLIS)
            .isAtLeast(1_000L)
    }

    @Test
    fun `blocked website opens FocusGuard notice with deferred browser redirect`() {
        val intent = BlockingAccessibilityService.createBlockNoticeIntent(
            context = context,
            strictBlock = false,
            blockedPackage = null,
            blockedDomain = "youtube.com",
            redirectBrowserPackage = CHROME_PACKAGE
        )

        assertThat(intent.component?.className).isEqualTo(BlockNoticeActivity::class.java.name)
        assertThat(intent.getStringExtra(BlockingAccessibilityService.EXTRA_BLOCKED_DOMAIN))
            .isEqualTo("youtube.com")
        assertThat(
            intent.getStringExtra(
                BlockingAccessibilityService.EXTRA_REDIRECT_BROWSER_PACKAGE
            )
        ).isEqualTo(CHROME_PACKAGE)
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
    }

    @Test
    fun `safe redirect opens Google in the browser that exposed the blocked site`() {
        val intent = BlockingAccessibilityService.createSafeRedirectIntent(CHROME_PACKAGE)

        assertThat(intent.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent.data?.toString()).isEqualTo("https://www.google.com")
        assertThat(intent.`package`).isEqualTo(CHROME_PACKAGE)
        assertThat(intent.categories).contains(Intent.CATEGORY_BROWSABLE)
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP).isNotEqualTo(0)
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP).isNotEqualTo(0)
    }

    @Test
    fun `blocked app notice does not request a browser redirect`() {
        val intent = BlockingAccessibilityService.createBlockNoticeIntent(
            context = context,
            strictBlock = false,
            blockedPackage = "com.example.blocked",
            blockedDomain = null,
            redirectBrowserPackage = null
        )

        assertThat(
            intent.hasExtra(BlockingAccessibilityService.EXTRA_REDIRECT_BROWSER_PACKAGE)
        ).isFalse()
    }

    private companion object {
        const val CHROME_PACKAGE = "com.android.chrome"
    }
}
