package com.focusguard.ui.compose.components

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.focusguard.monetization.FocusGuardAds
import com.focusguard.monetization.RewardedGateStateStore

/**
 * Gate explícito de rewarded ads. Cada anúncio exige um novo toque do usuário.
 * Fechar/pular um anúncio não concede progresso, e o progresso já conquistado
 * sobrevive a recriações da Activity e à morte do processo.
 */
@Composable
fun RewardedAdGateDialog(
    gateKey: String,
    requiredAds: Int,
    title: String,
    description: String,
    onComplete: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val target = requiredAds.coerceAtLeast(1)
    var watched by remember(gateKey, target) {
        mutableIntStateOf(
            if (RewardedGateStateStore.hasCredit(context, gateKey)) {
                target
            } else {
                RewardedGateStateStore.progress(context, gateKey).coerceAtMost(target - 1)
            }
        )
    }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var completionDelivered by remember { mutableStateOf(false) }

    LaunchedEffect(watched, target, gateKey) {
        if (watched >= target && !completionDelivered) {
            completionDelivered = true
            onComplete()
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (!loading && watched < target) onDismiss()
        },
        title = { Text(title) },
        text = {
            Text(
                buildString {
                    append(description)
                    append("\n\nAnúncios concluídos: ")
                    append(watched.coerceAtMost(target))
                    append('/')
                    append(target)
                    if (watched in 1 until target) {
                        append("\nSeu progresso ficará salvo se você sair agora.")
                    }
                    error?.let {
                        append("\n\n")
                        append(it)
                    }
                }
            )
        },
        confirmButton = {
            Button(
                enabled = !loading && watched < target && activity != null,
                onClick = {
                    val host = activity ?: return@Button
                    loading = true
                    error = null
                    FocusGuardAds.showRewarded(
                        activity = host,
                        onRewardEarned = {
                            loading = false
                            val completed = RewardedGateStateStore.recordReward(
                                context = context,
                                gateKey = gateKey,
                                requiredAds = target
                            )
                            watched = if (completed) {
                                target
                            } else {
                                RewardedGateStateStore.progress(context, gateKey)
                                    .coerceAtMost(target - 1)
                            }
                        },
                        onClosedWithoutReward = {
                            loading = false
                            error = "O anúncio foi fechado antes da recompensa. Tente novamente."
                        },
                        onUnavailable = { message ->
                            loading = false
                            error = message
                        }
                    )
                }
            ) {
                if (loading) {
                    CircularProgressIndicator()
                } else {
                    Text(
                        if (watched + 1 >= target) {
                            "Assistir último anúncio"
                        } else {
                            "Assistir próximo anúncio"
                        }
                    )
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !loading, onClick = onDismiss) {
                Text("Agora não")
            }
        }
    )
}

private tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
