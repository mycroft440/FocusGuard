package com.focusguard.ui

import androidx.compose.runtime.Composable
import com.focusguard.security.BlockTargetPolicy
import com.focusguard.ui.compose.screens.SelectableAppUi

/**
 * Transitional overload for callers that used to opt into the app → website
 * modal. The choice now lives exclusively beside duration configuration, so the
 * old flag is intentionally ignored while those callers retain their target
 * selection behavior.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun AppSelectionStep(
    onNext: (List<SelectableAppUi>, List<String>) -> Unit,
    onBack: () -> Unit,
    initialSelectedPackages: Set<String> = emptySet(),
    allowCompatibleProtection: Boolean = false,
    kinds: BlockTargetPolicy.Kinds = BlockTargetPolicy.APPS_ONLY,
    initialRules: List<String> = emptyList(),
    offerWebsiteCompanion: Boolean
) {
    AppSelectionStep(
        onNext = onNext,
        onBack = onBack,
        initialSelectedPackages = initialSelectedPackages,
        allowCompatibleProtection = allowCompatibleProtection,
        kinds = kinds,
        initialRules = initialRules
    )
}
