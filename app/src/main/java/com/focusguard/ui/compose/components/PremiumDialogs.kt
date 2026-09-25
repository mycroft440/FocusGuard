package com.focusguard.ui.compose.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.focusguard.monetization.PremiumManager
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.DarkCard
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary

private val PremiumGold = Color(0xFFF5C451)
private val PremiumGoldDeep = Color(0xFFE0922F)

private val premiumBenefits = listOf(
    "Sem anúncios em nenhuma tela",
    "Funções liberadas sem assistir anúncios",
    "Apoia o desenvolvimento do app"
)

/** Tela do Modo Premium: compra no Google Play ou código promocional. */
@Composable
fun PremiumDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val isPremium by PremiumManager.isPremiumFlow.collectAsStateWithInitial(
        PremiumManager.isPremium(context)
    )
    var code by remember { mutableStateOf("") }
    var codeError by remember { mutableStateOf(false) }
    var purchasing by remember { mutableStateOf(false) }
    var showSuccess by remember { mutableStateOf(false) }

    if (showSuccess) {
        PremiumActivatedDialog(onDismiss = {
            showSuccess = false
            onDismiss()
        })
        return
    }

    fun applyCode() {
        if (PremiumManager.redeemPromoCode(context, code)) {
            codeError = false
            showSuccess = true
        } else {
            codeError = true
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = DarkCard,
            border = BorderStroke(1.dp, PremiumGold.copy(alpha = 0.45f))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PremiumBadge(size = 64)
                Spacer(Modifier.height(16.dp))
                Text(
                    "Modo Premium",
                    color = TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (isPremium) "Ativo neste aparelho. Obrigado pelo apoio!"
                    else "Use o HardBlock sem anúncios.",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(18.dp))
                premiumBenefits.forEach { BenefitRow(it) }

                if (!isPremium) {
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = {
                            val activity = context.findActivity() ?: return@Button
                            purchasing = true
                            PremiumManager.purchase(activity) { result ->
                                activity.runOnUiThread {
                                    purchasing = false
                                    when (result) {
                                        PremiumManager.PurchaseResult.ACTIVATED ->
                                            showSuccess = true
                                        PremiumManager.PurchaseResult.PENDING -> toast(
                                            context,
                                            "Pagamento pendente. O Premium é ativado assim que ele for confirmado."
                                        )
                                        PremiumManager.PurchaseResult.CANCELLED -> Unit
                                        PremiumManager.PurchaseResult.UNAVAILABLE -> toast(
                                            context,
                                            "Compra indisponível agora. Instale o app pelo Google Play e tente de novo."
                                        )
                                    }
                                }
                            }
                        },
                        enabled = !purchasing,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PremiumGold,
                            contentColor = DarkBg
                        )
                    ) {
                        Text(
                            if (purchasing) "Abrindo o Google Play…" else "Comprar o Premium",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.height(20.dp))
                    Text(
                        "Tem um código promocional?",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = code,
                            onValueChange = {
                                code = it
                                codeError = false
                            },
                            placeholder = { Text("Código") },
                            singleLine = true,
                            isError = codeError,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { applyCode() }),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { applyCode() },
                            enabled = code.isNotBlank(),
                            border = BorderStroke(1.dp, AccentCyan),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text("Aplicar", color = AccentCyan) }
                    }
                    if (codeError) {
                        Text(
                            "Código inválido.",
                            color = Color(0xFFFF6B6B),
                            fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDismiss) {
                    Text("Fechar", color = TextSecondary)
                }
            }
        }
    }
}

/** Diálogo de comemoração exibido quando o Premium é ativado. */
@Composable
fun PremiumActivatedDialog(onDismiss: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "premiumPulse")
    val scale by pulse.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "premiumScale"
    )
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = DarkCard,
            border = BorderStroke(1.dp, PremiumGold.copy(alpha = 0.6f))
        ) {
            Column(
                modifier = Modifier
                    .background(
                        Brush.verticalGradient(
                            listOf(PremiumGold.copy(alpha = 0.16f), Color.Transparent)
                        )
                    )
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.scale(scale)) { PremiumBadge(size = 88) }
                Spacer(Modifier.height(20.dp))
                Text(
                    "Premium ativado!",
                    color = PremiumGold,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Todos os anúncios foram desativados e nenhuma função precisa mais de anúncio para ser liberada.",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 21.sp
                )
                Spacer(Modifier.height(18.dp))
                premiumBenefits.forEach { BenefitRow(it) }
                Spacer(Modifier.height(22.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PremiumGold,
                        contentColor = DarkBg
                    )
                ) { Text("Aproveitar", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun PremiumBadge(size: Int) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(
                Brush.linearGradient(listOf(PremiumGold, PremiumGoldDeep)),
                CircleShape
            )
            .border(2.dp, PremiumGold.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.WorkspacePremium,
            contentDescription = null,
            tint = DarkBg,
            modifier = Modifier.size((size * 0.55f).dp)
        )
    }
}

@Composable
private fun BenefitRow(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = PremiumGold,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(text, color = TextPrimary, fontSize = 14.sp)
    }
}

@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateWithInitial(initial: T) =
    androidx.compose.runtime.produceState(initial, this) { collect { value = it } }

private fun toast(context: Context, message: String) =
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
