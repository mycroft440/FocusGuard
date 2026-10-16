package com.focusguard.analytics

import com.focusguard.sitesblocker.UnsupportedBrowserActivity
import com.focusguard.ui.BlockNoticeActivity
import com.focusguard.ui.GenericBlockNoticeActivity
import com.focusguard.ui.MasterRemovalActivity
import com.focusguard.ui.PasswordUnlockActivity
import com.focusguard.ui.UsageImpactActivity

/**
 * As telas que interceptam uma tentativa (bloqueio, senha, impacto, proteção) não
 * são uso do HardBlock: a pessoa não abriu o app, foi parada por ele. Nas métricas
 * elas viram um "pacote" à parte, que encerra o trecho do app anterior mas nunca
 * entra em ranking nem em acessos.
 */
internal object BlockSurfaceUsage {
    const val SURFACE_PACKAGE = "::focusguard-block-surface"

    /**
     * Um app que deu lugar a uma tela de bloqueio em até este tempo depois de entrar
     * foi interceptado na entrada: não houve acesso de verdade.
     */
    const val INTERCEPTED_ENTRY_MILLIS = 5_000L

    private val surfaceClassNames = setOf(
        BlockNoticeActivity::class.java.name,
        GenericBlockNoticeActivity::class.java.name,
        PasswordUnlockActivity::class.java.name,
        UsageImpactActivity::class.java.name,
        MasterRemovalActivity::class.java.name,
        UnsupportedBrowserActivity::class.java.name
    )

    fun isSurface(packageName: String?, className: String?, ownPackage: String?): Boolean =
        ownPackage != null && packageName == ownPackage &&
            className != null && className in surfaceClassNames

    fun attributedPackage(packageName: String?, className: String?, ownPackage: String?): String? =
        if (isSurface(packageName, className, ownPackage)) SURFACE_PACKAGE else packageName
}
