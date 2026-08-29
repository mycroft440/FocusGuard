package com.focusguard.monetization

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.focusguard.ui.RewardedGateActivity
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Mantém a ação de configuração no processo enquanto uma Activity dedicada
 * conduz a sequência de rewarded ads. Se o processo morrer, a ação é descartada
 * e nada é liberado indevidamente.
 */
object RewardedGateCoordinator {
    private val pendingActions = ConcurrentHashMap<String, () -> Unit>()

    fun launch(
        context: Context,
        requiredAds: Int,
        title: String,
        description: String,
        action: () -> Unit
    ) {
        val token = UUID.randomUUID().toString()
        pendingActions[token] = action
        val intent = RewardedGateActivity.createIntent(
            context = context,
            token = token,
            requiredAds = requiredAds,
            title = title,
            description = description
        )
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { pendingActions.remove(token) }
    }

    fun complete(token: String) {
        pendingActions.remove(token)?.invoke()
    }

    fun cancel(token: String) {
        pendingActions.remove(token)
    }
}
