package com.focusguard.ui.compose.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSelectionAlreadyBlockedTest {
    @Test
    fun alreadyBlockedAppsAreExposedInDedicatedTopSection() {
        val blocked = SelectableAppUi("com.facebook.katana", "Facebook", false, isAlreadyBlocked = true)
        val available = SelectableAppUi("com.instagram.android", "Instagram", false)
        assertEquals(listOf(blocked), alreadyBlockedAppsForSelection(listOf(available, blocked), ""))
    }
}
