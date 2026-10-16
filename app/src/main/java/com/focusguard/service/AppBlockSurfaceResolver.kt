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
    data class Resolution(
        val surface: AppBlockSurfacePolicy.Surface,
        /**
         * TIME commitments and active usage limits own a closed target: after the
         * interception succeeds the target Activity/task must not be preserved for
         * a later visit. A plain PASSWORD session is the opposite because its
         * one-visit grant intentionally returns to the same target task.
         */
        val closeTargetAfterInterception: Boolean,
        /**
         * False quando nenhuma proteção segura o app agora: o conjunto rápido do
         * serviço estava velho (um período que acabou, um limite que zerou à
         * meia-noite, um bloqueio removido). Nesse caso não existe bloqueio a mostrar
         * e a tentativa precisa ser liberada, nunca virar uma tela de bloqueio.
         */
        val hasLiveOwner: Boolean = true,
        /**
         * Se a tentativa abre a tela de Impacto (bloqueio por tempo ou limite sem
         * senha), já consultado aqui; null quando não há dono a consultar. A tela
         * genérica usa a resposta em vez de refazer a mesma consulta.
         */
        val showsUsageImpact: Boolean? = null,
        /**
         * O dono é um limite diário esgotado configurado para liberar com a senha
         * mestra, sem bloqueio por tempo nem Modo Foco por cima: a tela de bloqueio
         * oferece a senha, que libera o app até a meia-noite.
         */
        val offersLimitPasswordUnlock: Boolean = false
    ) {
        /** True only while PASSWORD is still the dominant owner of this attempt. */
        val allowsPasswordVisit: Boolean
            get() = surface == AppBlockSurfacePolicy.Surface.PASSWORD_UNLOCK &&
                !closeTargetAfterInterception
    }

    private val appContext = context.applicationContext

    suspend fun resolve(
        blockedPackage: String?
    ): AppBlockSurfacePolicy.Surface = resolveAttempt(
        blockedPackage = blockedPackage
    ).surface

    suspend fun resolveAttempt(
        blockedPackage: String?
    ): Resolution {
        val packageName = blockedPackage?.takeIf(String::isNotBlank)
            ?: return Resolution(
                surface = AppBlockSurfacePolicy.Surface.GENERIC_BLOCK,
                closeTargetAfterInterception = false
            )

        // This lookup gives password-protected daily limits precedence over a
        // PASSWORD session for the same package. Only PASSWORD_SESSION may enter
        // the target-credential Activity.
        val credentialOrigin = sessionManager.credentialUnlockOrigin(
            blockedPackage = packageName,
            blockedDomain = null
        )

        // This is the live, stateful check. It only reports a TIME session while
        // its current schedule window is active, and it only reports a daily limit
        // after the allowance is exhausted while its selected post-limit behavior
        // is actually blocking now. A configured-but-inactive rule must never win.
        // Consultada também com um limite com senha esgotado: um bloqueio por tempo
        // no mesmo app fica acima do limite, e a tentativa é desse bloqueio (antes a
        // consulta era pulada e o app caía na tela genérica, sem o Impacto). Para o
        // limite com senha em si a resposta é sempre não.
        val timedOrUsageInterventionBlocksTarget =
            UsageImpactRouter.shouldShowForBlockedApp(appContext, packageName)

        val focusModeBlocksTarget = FocusModeStore.readSession(appContext)
            ?.takeIf { it.isActive() }
            ?.blockedPackages
            ?.contains(packageName) == true

        // Só procura uma sessão quando nada acima respondeu: é a mesma regra da
        // reconciliação e cobre tipos de sessão que não abrem a tela de impacto.
        val hasLiveOwner = credentialOrigin != null ||
            timedOrUsageInterventionBlocksTarget ||
            focusModeBlocksTarget ||
            sessionManager.hasEnforcingSessionForApp(packageName)
        if (!hasLiveOwner) {
            return Resolution(
                surface = AppBlockSurfacePolicy.Surface.GENERIC_BLOCK,
                closeTargetAfterInterception = false,
                hasLiveOwner = false
            )
        }

        if (credentialOrigin != BiometricAppUnlockPolicy.BlockOrigin.PASSWORD_SESSION) {
            return Resolution(
                surface = AppBlockSurfacePolicy.decide(
                    AppBlockSurfacePolicy.Facts(
                        focusModeBlocksTarget = false,
                        dopamineFastBlocksTarget = false,
                        activeUsageLimitBlocksTarget = false,
                        credentialOrigin = credentialOrigin
                    )
                ),
                closeTargetAfterInterception = shouldCloseTargetAfterInterception(
                    credentialOrigin = credentialOrigin,
                    timedOrUsageInterventionBlocksTarget =
                        timedOrUsageInterventionBlocksTarget,
                    dopamineFastBlocksTarget = false
                ),
                showsUsageImpact = timedOrUsageInterventionBlocksTarget,
                offersLimitPasswordUnlock = offersLimitPasswordUnlock(
                    credentialOrigin = credentialOrigin,
                    timedOrUsageInterventionBlocksTarget =
                        timedOrUsageInterventionBlocksTarget,
                    focusModeBlocksTarget = focusModeBlocksTarget
                )
            )
        }

        // TIME no longer consults BlockOverview here. The overview is an inventory
        // of configured rules and can legitimately contain a recurring TIME rule
        // outside its current blocking window. UsageImpactRouter above already
        // resolves the live TIME owner and therefore avoids a false permanent block.
        val activeTimedOrUsageBlock = timedOrUsageInterventionBlocksTarget

        return Resolution(
            surface = AppBlockSurfacePolicy.decide(
                AppBlockSurfacePolicy.Facts(
                    focusModeBlocksTarget = focusModeBlocksTarget,
                    dopamineFastBlocksTarget = false,
                    activeUsageLimitBlocksTarget = activeTimedOrUsageBlock,
                    credentialOrigin = credentialOrigin
                )
            ),
            closeTargetAfterInterception = shouldCloseTargetAfterInterception(
                credentialOrigin = credentialOrigin,
                timedOrUsageInterventionBlocksTarget = activeTimedOrUsageBlock,
                dopamineFastBlocksTarget = false
            ),
            showsUsageImpact = activeTimedOrUsageBlock
        )
    }

    companion object {
        /**
         * A senha mestra só é oferecida quando o limite com senha é o dono mais forte:
         * com um bloqueio por tempo ou o Modo Foco por cima, a senha liberaria o limite
         * e o app continuaria fechado.
         */
        internal fun offersLimitPasswordUnlock(
            credentialOrigin: BiometricAppUnlockPolicy.BlockOrigin?,
            timedOrUsageInterventionBlocksTarget: Boolean,
            focusModeBlocksTarget: Boolean
        ): Boolean = credentialOrigin ==
            BiometricAppUnlockPolicy.BlockOrigin.USAGE_LIMIT_PASSWORD_UNLOCK &&
            !timedOrUsageInterventionBlocksTarget &&
            !focusModeBlocksTarget

        internal fun shouldCloseTargetAfterInterception(
            credentialOrigin: BiometricAppUnlockPolicy.BlockOrigin?,
            timedOrUsageInterventionBlocksTarget: Boolean,
            dopamineFastBlocksTarget: Boolean
        ): Boolean =
            credentialOrigin ==
                BiometricAppUnlockPolicy.BlockOrigin.USAGE_LIMIT_PASSWORD_UNLOCK ||
                timedOrUsageInterventionBlocksTarget ||
                dopamineFastBlocksTarget
    }
}
