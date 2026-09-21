package com.focusguard.accessibility.website.redirection

import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebsiteRedirectDestinationStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        WebsiteRedirectDestinationStore.reset(context)
    }

    @After
    fun tearDown() {
        WebsiteRedirectDestinationStore.reset(context)
    }

    @Test
    fun `missing scheme is normalized to https root`() {
        val destination = WebsiteRedirectDestinationStore.destinationFromUserInput("Example.org")

        assertThat(destination).isNotNull()
        assertThat(destination?.url).isEqualTo("https://example.org/")
    }

    @Test
    fun `save publishes destination and increments revision`() {
        val before = WebsiteRedirectDestinationStore.revision(context)

        val result = WebsiteRedirectDestinationStore.save(context, "https://example.org/")

        assertThat(result).isEqualTo(WebsiteRedirectDestinationStore.SaveResult.SAVED)
        assertThat(WebsiteRedirectDestination.current.url).isEqualTo("https://example.org/")
        assertThat(WebsiteRedirectDestinationStore.revision(context)).isEqualTo(before + 1L)
    }

    @Test
    fun `blocked destination is rejected without replacing current`() {
        val result = WebsiteRedirectDestinationStore.save(
            context = context,
            rawUrl = "https://example.org/",
            activeBlockedRules = setOf("example.org")
        )

        assertThat(result)
            .isEqualTo(WebsiteRedirectDestinationStore.SaveResult.BLOCKED_BY_ACTIVE_RULE)
        assertThat(WebsiteRedirectDestination.current).isEqualTo(WebsiteRedirectDestination.GOOGLE)
    }

    @Test
    fun `later rule change suspends already configured destination`() {
        assertThat(
            WebsiteRedirectDestinationStore.save(context, "https://example.org/")
        ).isEqualTo(WebsiteRedirectDestinationStore.SaveResult.SAVED)

        assertThat(
            WebsiteRedirectDestinationStore.currentConflictsWith(setOf("example.org"))
        ).isTrue()
        assertThat(
            WebsiteRedirectDestinationStore.currentConflictsWith(setOf("allowed.example"))
        ).isFalse()
    }

    @Test
    fun `temporary password grant never turns blocked destination into redirect bypass`() {
        assertThat(
            WebsiteRedirectDestinationStore.save(context, "https://example.org/")
        ).isEqualTo(WebsiteRedirectDestinationStore.SaveResult.SAVED)

        assertThat(
            WebsiteRedirectDestinationStore.currentConflictsWith(setOf("example.org"))
        ).isTrue()
    }

    @Test
    fun `credentials path query and non web schemes are rejected`() {
        assertThat(
            WebsiteRedirectDestinationStore.destinationFromUserInput(
                "https://user@example.org/"
            )
        ).isNull()
        assertThat(
            WebsiteRedirectDestinationStore.destinationFromUserInput(
                "https://example.org/path"
            )
        ).isNull()
        assertThat(
            WebsiteRedirectDestinationStore.destinationFromUserInput(
                "https://example.org/?x=1"
            )
        ).isNull()
        assertThat(
            WebsiteRedirectDestinationStore.destinationFromUserInput(
                "intent://example.org/"
            )
        ).isNull()
    }

    @Test
    fun `http root is accepted when explicitly supplied`() {
        val destination = WebsiteRedirectDestinationStore.destinationFromUserInput(
            "http://example.org/"
        )

        assertThat(destination?.url).isEqualTo("http://example.org/")
        assertThat(destination?.matchesSurface("http://example.org/")).isTrue()
        assertThat(destination?.matchesSurface("https://example.org/")).isFalse()
    }
}
