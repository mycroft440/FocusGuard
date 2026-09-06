package com.focusguard.service

import android.content.Context
import com.focusguard.focusmode.FocusModeStore
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.AppBlockSurfacePolicy
import com.focusguard.security.BiometricAppUnlockPolicy
import com.focusguard.usage.UsageImpactRouter

/**
 * Resolves the owner of an app interception before any blocking UI is rendered.
 *
 * The Accessibility service still shares a fast aggregate set of blocked package
 * names, but that set is never treated as the reason for the block here. The
 * responsible protection is re-resolved from its own state so PASSWORD can have
 * a dedicated authentication Activity without weakening stronger protections.
 */
internal class AppBlockSurfaceResolver(
    context: Context,
    private val sessionManager: BlockingSessionManager
) {
    private val appContext = context.applicationContext

    suspend fun resolve(
        blockedPackage: String?,
        strictPomodoroActive: Boolean
    ): AppBlockSurfacePolicy.Surface {
        val packageName = blockedPackage?.takeIf(String::isNotBlank)
            ?: return AppBlockSurfacePolicy.Surface.GENERIC_BLOCK

        if (strictPomodoroActive) {
            return AppBlockSurfacePolicy.Surface.GENERIC_BLOCK
        }

        // This lookup gives password-protected daily limits precedence over a
        // PASSWORD session for the same package. Only PASSWORD_SESSION may enter
        // the target-credential Activity.
        val credentialOrigin = sessionManager.credentialUnlockOrigin(
            blockedPackage = packageName,
            blockedDomain = null,
            strictPomodoroActive = false
        )
        if (credentialOrigin != BiometricAppUnlockPolicy.BlockOrigin.PASSWORD_SESSION) {
            return AppBlockSurfacePolicy.decide(
                AppBlockSurfacePolicy.Facts(
                    strictPomodoro = false,
                    focusModeBlocksTarget = false,
                    dopamineFastBlocksTarget = false,
                    activeUsageLimitBlocksTarget = false,
                    credentialOrigin = credentialOrigin
                )
            )
        }

        val focusModeBlocksTarget = FocusModeStore.readSession(appContext)
            ?.takeIf { it.isActive() }
            ?.blockedPackages
            ?.contains(packageName) == true

        // A legacy database can contain a PASSWORD session overlapping a TIME
        // commitment. Keep that target on the non-interactive blocking surface.
        val dopamineFastBlocksTarget = sessionManager.getBlockOverview()
            .dopamineFastEntries
            .any { entry -> !entry.isWebsite && entry.identifier == packageName }

        // UsageImpactRouter intentionally ignores PASSWORD-mode limits. Those were
        // already handled by credentialUnlockOrigin above. Here it detects active
        // non-password/time limits that may legally coexist with a PASSWORD session.
        val activeUsageLimitBlocksTarget = UsageImpactRouter.shouldShowForBlockedApp(
            appContext,
            packageName
        )

        return AppBlockSurfacePolicy.decide(
            AppBlockSurfacePolicy.Facts(
                strictPomodoro = false,
                focusModeBlocksTarget = focusModeBlocksTarget,
                dopamineFastBlocksTarget = dopamineFastBlocksTarget,
                activeUsageLimitBlocksTarget = activeUsageLimitBlocksTarget,
                credentialOrigin = credentialOrigin
            )
        )
    }
}
