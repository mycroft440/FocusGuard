package com.focusguard.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PermissionUtilsTest {

    @Test
    fun `missing essential permissions returns only permissions not granted`() {
        assertThat(
            PermissionUtils.missingEssentialPermissions(
                accessibilityEnabled = false,
                usageAccessEnabled = false
            )
        ).containsExactly(
            EssentialPermission.ACCESSIBILITY,
            EssentialPermission.USAGE_ACCESS
        ).inOrder()

        assertThat(
            PermissionUtils.missingEssentialPermissions(
                accessibilityEnabled = true,
                usageAccessEnabled = false
            )
        ).containsExactly(EssentialPermission.USAGE_ACCESS)

        assertThat(
            PermissionUtils.missingEssentialPermissions(
                accessibilityEnabled = false,
                usageAccessEnabled = true
            )
        ).containsExactly(EssentialPermission.ACCESSIBILITY)

        assertThat(
            PermissionUtils.missingEssentialPermissions(
                accessibilityEnabled = true,
                usageAccessEnabled = true
            )
        ).isEmpty()
    }

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
