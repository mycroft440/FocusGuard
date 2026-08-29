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

/**
 * Gate explícito de rewarded ads. Cada anúncio exige um novo toque do usuário.
 * Fechar/pular um anúncio não concede progresso.
 */
@Composable
fun RewardedAdGateDialog(
    requiredAds: Int,
    title: String,
    description: String,
    onComplete: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var watched by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var completionDelivered by remember { mutableStateOf(false) }
    val target = requiredAds.coerceAtLeast(1)

    LaunchedEffect(watched, target) {
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
                    append(watched)
                    append('/')
                    append(target)
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
                            watched += 1
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
                    Text(if (watched + 1 >= target) "Assistir último anúncio" else "Assistir próximo anúncio")
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
