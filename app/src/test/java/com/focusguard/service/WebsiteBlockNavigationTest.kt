package com.focusguard.service

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.focusguard.data.PredefinedWebsites
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
    fun `accessibility window events are requested without delivery debounce`() {
        val eventTypes = BlockingAccessibilityService.requestedAccessibilityEventTypes()

        assertThat(
            eventTypes and AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ).isNotEqualTo(0)
        assertThat(
            eventTypes and AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ).isNotEqualTo(0)
        assertThat(BlockingAccessibilityService.EVENT_NOTIFICATION_TIMEOUT_MILLIS)
            .isEqualTo(0L)
    }

    @Test
    fun `website blocking listens to address bar events with no delivery debounce`() {
        val immediateTypes = BlockingAccessibilityService.immediateBrowserBlockEventTypesForTest()
        val requested = BlockingAccessibilityService.requestedAccessibilityEventTypes()

        assertThat(immediateTypes).containsAtLeast(
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED
        )
        immediateTypes.forEach { type ->
            assertThat(requested and type).isNotEqualTo(0)
        }
        assertThat(BlockingAccessibilityService.EVENT_NOTIFICATION_TIMEOUT_MILLIS).isEqualTo(0L)
    }

    @Test
    fun `blocked domain is classified immediately from address bar`() {
        assertThat(
            BlockingAccessibilityService.immediateWebsiteBlockTarget(
                addressText = "https://m.facebook.com/profile",
                url = "https://m.facebook.com/profile",
                blockedRules = listOf("facebook.com")
            )
        ).isEqualTo("m.facebook.com")
    }

    @Test
    fun `pornography search is classified before navigation finishes`() {
        assertThat(
            BlockingAccessibilityService.immediateWebsiteBlockTarget(
                addressText = "free porn videos",
                url = null,
                blockedRules = listOf(PredefinedWebsites.PORNOGRAPHY_RULE)
            )
        ).isEqualTo(PredefinedWebsites.PORNOGRAPHY_RULE)
    }

    @Test
    fun `safe address is not blocked by the immediate classifier`() {
        assertThat(
            BlockingAccessibilityService.immediateWebsiteBlockTarget(
                addressText = "https://example.com/news",
                url = "https://example.com/news",
                blockedRules = listOf("facebook.com", PredefinedWebsites.PORNOGRAPHY_RULE)
            )
        ).isNull()
    }

    @Test
    fun `settings interception listens to the earliest window signals`() {
        // The race the user can win is measured in frames: every event type the
        // guard ignores is time in which the switch that disables this service is
        // already on screen. TYPE_WINDOWS_CHANGED and TYPE_VIEW_FOCUSED arrive
        // before TYPE_WINDOW_STATE_CHANGED and cost nothing extra to observe.
        val interceptionTypes = BlockingAccessibilityService.settingsInterceptionEventTypesForTest()

        assertThat(interceptionTypes).containsAtLeast(
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED
        )
    }

    @Test
    fun `every interception event type is actually subscribed`() {
        // Listening for an event the service never receives would be a silent hole.
        val requested = BlockingAccessibilityService.requestedAccessibilityEventTypes()

        BlockingAccessibilityService.settingsInterceptionEventTypesForTest().forEach { type ->
            assertThat(requested and type).isNotEqualTo(0)
        }
    }

    @Test
    fun `transition guard covers the protection handoff without restarting settings`() {
        // The new flow never cold-starts Settings. It only needs to cover BACK,
        // the delayed HOME action and the stable notice while follow-up window
        // events from the intercepted attempt are still being delivered.
        assertThat(BlockingAccessibilityService.settingsTransitionGuardMillisForTest())
            .isAtLeast(2_000L)
    }

    @Test
    fun `blocking refresh carries an immediate in-memory snapshot`() {
        val intent = BlockingAccessibilityService.createRefreshBlockingIntent(
            context = context,
            blockedApps = listOf("com.example.blocked"),
            blockedSites = listOf("https://www.YouTube.com/watch?v=1"),
            blockingActive = true,
            strictPomodoro = false
        )

        assertThat(intent.`package`).isEqualTo(context.packageName)
        assertThat(
            intent.getStringArrayListExtra(
                BlockingAccessibilityService.EXTRA_BLOCKED_APPS_SNAPSHOT
            )
        ).containsExactly("com.example.blocked")
        assertThat(
            intent.getStringArrayListExtra(
                BlockingAccessibilityService.EXTRA_BLOCKED_SITES_SNAPSHOT
            )
        ).containsExactly("youtube.com")
        assertThat(
            intent.getBooleanExtra(
                BlockingAccessibilityService.EXTRA_BLOCKING_ACTIVE_SNAPSHOT,
                false
            )
        ).isTrue()
    }

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
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP).isNotEqualTo(0)
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK).isEqualTo(0)
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
