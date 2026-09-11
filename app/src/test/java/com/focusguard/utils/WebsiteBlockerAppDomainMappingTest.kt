package com.focusguard.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebsiteBlockerAppDomainMappingTest {
    @Test
    fun facebookAppAlsoMapsToFacebookWebsite() {
        assertTrue(WebsiteBlocker.domainRulesForAppPackages(listOf("com.facebook.katana")).contains("facebook.com"))
    }

    @Test
    fun facebookWebsiteAlsoMapsToFacebookApp() {
        assertEquals("facebook.com", WebsiteBlocker.appPackageDomainsFor(listOf("facebook.com"))["com.facebook.katana"])
    }
}
