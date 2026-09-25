package com.focusguard.admin

import android.os.UserManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UnknownSourcesSecurityManagerTest {

    @Test
    fun `android 8 and 9 use the per-user unknown sources restriction`() {
        assertThat(UnknownSourcesSecurityManager.restrictionForSdk(26))
            .isEqualTo(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
        assertThat(UnknownSourcesSecurityManager.restrictionForSdk(28))
            .isEqualTo(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
    }

    @Test
    fun `android 10 and newer use the device-wide unknown sources restriction`() {
        assertThat(UnknownSourcesSecurityManager.restrictionForSdk(29))
            .isEqualTo(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
        assertThat(UnknownSourcesSecurityManager.restrictionForSdk(36))
            .isEqualTo(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
    }
}
