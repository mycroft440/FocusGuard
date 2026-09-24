package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LauncherAppIconCompatibilityPolicyTest {

    @Test
    fun `ordinary MIUI ShortcutIcon remains eligible for blocked app interception`() {
        assertThat(
            ImmediateInterceptionPolicy.isLikelyLauncherAppIconClass(
                "com.miui.home.launcher.ShortcutIcon"
            )
        ).isTrue()
    }

    @Test
    fun `unknown OEM icon class remains eligible when exact app identity matches later`() {
        assertThat(
            ImmediateInterceptionPolicy.isLikelyLauncherAppIconClass(
                "com.vendor.launcher.CustomHomeItemView"
            )
        ).isTrue()
        assertThat(
            ImmediateInterceptionPolicy.isLikelyLauncherAppIconClass("")
        ).isTrue()
    }

    @Test
    fun `folder widget and deep shortcut surfaces stay outside app icon fast path`() {
        assertThat(
            ImmediateInterceptionPolicy.isLikelyLauncherAppIconClass(
                "com.android.launcher3.folder.FolderIcon"
            )
        ).isFalse()
        assertThat(
            ImmediateInterceptionPolicy.isLikelyLauncherAppIconClass(
                "com.android.launcher3.widget.WidgetCell"
            )
        ).isFalse()
        assertThat(
            ImmediateInterceptionPolicy.isLikelyLauncherAppIconClass(
                "com.android.launcher3.shortcuts.DeepShortcutView"
            )
        ).isFalse()
    }
}
