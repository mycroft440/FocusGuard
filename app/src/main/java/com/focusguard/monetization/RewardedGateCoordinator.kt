package com.focusguard.monetization

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.focusguard.ui.RewardedGateActivity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Mantém a ação corrente em memória, mas separa a recompensa em um crédito
 * persistente. Assim, morte do processo não faz o usuário perder anúncios vistos.
 */
object RewardedGateCoordinator {
    private data class PendingAction(
        val gateKey: String,
        val action: () -> Unit
    )

    private val pendingActions = ConcurrentHashMap<String, PendingAction>()

    fun launch(
        context: Context,
        requiredAds: Int,
        title: String,
        description: String,
        action: () -> Unit
    ) {
        val target = requiredAds.coerceAtLeast(1)
        val gateKey = RewardedGateKeys.forRequest(title, target)

        // Um crédito conquistado anteriormente deve ser usado antes de pedir
        // qualquer novo anúncio.
        if (RewardedGateStateStore.consumeCredit(context, gateKey)) {
            action()
            return
        }

        val token = UUID.randomUUID().toString()
        pendingActions[token] = PendingAction(gateKey, action)
        val intent = RewardedGateActivity.createIntent(
            context = context,
            token = token,
            gateKey = gateKey,
            requiredAds = target,
            title = title,
            description = description
        )
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { pendingActions.remove(token) }
    }

    /**
     * Só consome o crédito quando a ação original ainda existe. Se o processo
     * morreu, o crédito permanece para a próxima tentativa do usuário.
     */
    fun complete(context: Context, token: String, gateKey: String) {
        val pending = pendingActions.remove(token) ?: return
        if (pending.gateKey != gateKey) return
        if (RewardedGateStateStore.consumeCredit(context, gateKey)) {
            pending.action()
        }
    }

    fun cancel(token: String) {
        pendingActions.remove(token)
    }
}
