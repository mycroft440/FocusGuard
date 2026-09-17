package com.focusguard.service

import com.focusguard.accessibility.website.identification.WebsiteIdentificationResult
import com.focusguard.utils.BrowserSurfaceInspector

/** Immutable data returned by the off-main accessibility inspection worker. */
internal data class BrowserInspectionOutcome(
    val snapshot: BrowserInspectionCoordinator.Snapshot,
    val identification: WebsiteIdentificationResult,
    val surface: BrowserSurfaceInspector.Surface,
    val addressBarPresent: Boolean,
    val focusedAddressEditor: Boolean,
    val recognizedBrowser: Boolean
)
