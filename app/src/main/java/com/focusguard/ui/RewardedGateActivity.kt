package com.focusguard.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.focusguard.monetization.RewardedGateCoordinator
import com.focusguard.monetization.RewardedGateStateStore
import com.focusguard.ui.compose.components.RewardedAdGateDialog
import com.focusguard.ui.compose.theme.FocusGuardTheme

class RewardedGateActivity : AppCompatActivity() {
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
        val gateKey = intent.getStringExtra(EXTRA_GATE_KEY).orEmpty()
        val requiredAds = intent.getIntExtra(EXTRA_REQUIRED_ADS, 1).coerceAtLeast(1)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Desbloquear recurso" }
        val description = intent.getStringExtra(EXTRA_DESCRIPTION).orEmpty()
        if (token.isBlank() || gateKey.isBlank()) {
            finish()
            return
        }

        // Se o crédito foi conquistado antes de uma recriação da Activity, conclui
        // imediatamente sem exigir outro anúncio. Se o processo anterior morreu,
        // o coordinator não terá a lambda e manterá o crédito para a próxima ação.
        if (RewardedGateStateStore.hasCredit(this, gateKey)) {
            completed = true
            RewardedGateCoordinator.complete(this, token, gateKey)
            finish()
            return
        }

        setContent {
            FocusGuardTheme {
                RewardedAdGateDialog(
                    gateKey = gateKey,
                    requiredAds = requiredAds,
                    title = title,
                    description = description,
                    onComplete = {
                        if (completed) return@RewardedAdGateDialog
                        completed = true
                        RewardedGateCoordinator.complete(this, token, gateKey)
                        finish()
                    },
                    onDismiss = {
                        RewardedGateCoordinator.cancel(token)
                        finish()
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing && !completed) {
            intent.getStringExtra(EXTRA_TOKEN)?.let(RewardedGateCoordinator::cancel)
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_TOKEN = "rewarded_gate_token"
        private const val EXTRA_GATE_KEY = "rewarded_gate_key"
        private const val EXTRA_REQUIRED_ADS = "rewarded_gate_required_ads"
        private const val EXTRA_TITLE = "rewarded_gate_title"
        private const val EXTRA_DESCRIPTION = "rewarded_gate_description"

        fun createIntent(
            context: Context,
            token: String,
            gateKey: String,
            requiredAds: Int,
            title: String,
            description: String
        ): Intent = Intent(context, RewardedGateActivity::class.java).apply {
            putExtra(EXTRA_TOKEN, token)
            putExtra(EXTRA_GATE_KEY, gateKey)
            putExtra(EXTRA_REQUIRED_ADS, requiredAds)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_DESCRIPTION, description)
        }
    }
}
