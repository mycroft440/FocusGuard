package com.focusguard.sitesblocker

import android.content.Context
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RejectedBrowsersTest {
    private lateinit var context: Context
    private val installed = mutableMapOf<String, String?>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs().edit().clear().commit()
        installed.clear()
    }

    @Test
    fun `rejected browser stays rejected without a new test`() {
        installed[BROWSER] = VERSION_1

        newList().markRejected(BROWSER)

        assertThat(newList().isRejected(BROWSER)).isTrue()
        assertThat(newList().isRejected(OTHER_BROWSER)).isFalse()
        assertThat(newList().isRejected(null)).isFalse()
    }

    @Test
    fun `update of the browser or the app tests it again`() {
        installed[BROWSER] = VERSION_1
        val list = newList()
        list.markRejected(BROWSER)

        installed[BROWSER] = VERSION_2

        assertThat(list.isRejected(BROWSER)).isFalse()
        assertThat(prefs().contains(BROWSER)).isFalse()
        assertThat(newList().isRejected(BROWSER)).isFalse()
    }

    @Test
    fun `unreadable version keeps the rejection`() {
        installed[BROWSER] = VERSION_1
        val list = newList()
        list.markRejected(BROWSER)

        installed[BROWSER] = null

        assertThat(list.isRejected(BROWSER)).isTrue()
    }

    @Test
    fun `identified family removes the rejection`() {
        installed[BROWSER] = VERSION_1
        val list = newList()
        list.markRejected(BROWSER)

        list.forget(BROWSER)

        assertThat(list.isRejected(BROWSER)).isFalse()
        assertThat(newList().isRejected(BROWSER)).isFalse()
    }

    @Test
    fun `entry of the previous format is rejected in the installed version`() {
        // Gravado como o IdentifiedBrowsers gravava antes: só "recusado", sem a versão.
        prefs().edit().putBoolean(BROWSER, true).commit()
        installed[BROWSER] = VERSION_1

        assertThat(newList().isRejected(BROWSER)).isTrue()
        assertThat(prefs().getString(BROWSER, null)).isEqualTo(VERSION_1)

        installed[BROWSER] = VERSION_2
        assertThat(newList().isRejected(BROWSER)).isFalse()
    }

    @Test
    fun `rejection without a readable version adopts the next one read`() {
        installed[BROWSER] = null
        newList().markRejected(BROWSER)

        installed[BROWSER] = VERSION_1
        assertThat(newList().isRejected(BROWSER)).isTrue()

        installed[BROWSER] = VERSION_2
        assertThat(newList().isRejected(BROWSER)).isFalse()
    }

    private fun newList(): RejectedBrowsers = RejectedBrowsers(context) { installed[it] }

    private fun prefs() =
        context.getSharedPreferences(RejectedBrowsers.PREFS_NAME, Context.MODE_PRIVATE)

    private companion object {
        const val BROWSER = "com.example.unsupported"
        const val OTHER_BROWSER = "com.example.other"
        val VERSION_1 = BrowserVersions.version(100L, 200L)
        val VERSION_2 = BrowserVersions.version(101L, 200L)
    }
}
