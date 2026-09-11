package com.focusguard.ui.compose.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeBlockTargetSummaryTest {
    @Test
    fun sitesAreIncludedWhenNoAppsAreSelected() {
        assertEquals(2, selectedTargetCount(appCount = 0, siteCount = 2))
    }
}
