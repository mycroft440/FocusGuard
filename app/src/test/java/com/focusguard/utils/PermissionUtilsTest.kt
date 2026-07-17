package com.focusguard.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PermissionUtilsTest {

    @Test
    fun `essential permissions require accessibility and usage access`() {
        assertThat(
            PermissionUtils.hasEssentialPermissions(
                accessibilityEnabled = true,
                usageAccessEnabled = true
            )
        ).isTrue()

        assertThat(
            PermissionUtils.hasEssentialPermissions(
                accessibilityEnabled = true,
                usageAccessEnabled = false
            )
        ).isFalse()

        assertThat(
            PermissionUtils.hasEssentialPermissions(
                accessibilityEnabled = false,
                usageAccessEnabled = true
            )
        ).isFalse()

        assertThat(
            PermissionUtils.hasEssentialPermissions(
                accessibilityEnabled = false,
                usageAccessEnabled = false
            )
        ).isFalse()
    }
}
