package com.focusguard.ui.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.DarkCard
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary

enum class LimitType {
    HARD_BLOCK_NO_PASSWORD,
    WARNING_ONLY,
    HARD_BLOCK_WITH_PASSWORD
}

data class BlockConfig(
    val dailyLimitHours: Int = 0,
    val daysLimit: Int = 0,
    val usePassword: Boolean = false,
    val limitType: LimitType = LimitType.WARNING_ONLY,
    val agreementText: String = ""
)

@Composable
fun UsageBlockConfigScreen(
    onCancel: () -> Unit,
    onSave: (config: BlockConfig) -> Unit,
    appName: String = "aplicativo",
    hasExistingPassword: Boolean = false,
    onCreateOrManagePassword: () -> Unit = {}
) {
    var dailyLimitHoursText by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(LimitType.WARNING_ONLY) }
    var daysToBlockText by remember { mutableStateOf("") }
    var agreementText by remember { mutableStateOf("") }

    val dailyLimitHours = dailyLimitHoursText.toIntOrNull() ?: 0
    val daysToBlock = (daysToBlockText.toIntOrNull() ?: 0).coerceIn(0, 120)
    val requiresPassword = selectedType == LimitType.HARD_BLOCK_WITH_PASSWORD
    val requiresDays = selectedType == LimitType.HARD_BLOCK_NO_PASSWORD
    val agreementValid = agreementText.trim().equals("eu concordo e entendo os riscos", ignoreCase = true)

    val canSave = dailyLimitHours > 0 && when (selectedType) {
        LimitType.WARNING_ONLY -> true
        LimitType.HARD_BLOCK_WITH_PASSWORD -> true
        LimitType.HARD_BLOCK_NO_PASSWORD -> daysToBlock in 1..120 && agreementValid
    }

    Scaffold(containerColor = DarkBg) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBg)
                .padding(innerPadding)
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Text(
                text = "Definir tempo máximo para $appName",
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Deseja limitar o tempo de uso do(s) aplicativo(s) em quanto tempo por dia?",
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = dailyLimitHoursText,
                        onValueChange = { value -> dailyLimitHoursText = value.filter { it.isDigit() } },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Ex: 2", color = TextHint) },
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Surface(
                        color = DangerRed.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            text = "! Aviso: você poderá usar o app somente a quantidade de tempo que você definir acima.",
                            color = DangerRed,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Que tipo de limite deseja?",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    LimitOption(
                        selected = selectedType == LimitType.HARD_BLOCK_NO_PASSWORD,
                        onSelect = { selectedType = LimitType.HARD_BLOCK_NO_PASSWORD },
                        text = "Desejo bloquear o app quando o tempo acabar no dia de forma a não poder desbloquear, pelo período que eu dizer em dias."
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    LimitOption(
                        selected = selectedType == LimitType.WARNING_ONLY,
                        onSelect = { selectedType = LimitType.WARNING_ONLY },
                        text = "Desejo apenas receber um aviso que já usei o app demais, que irá bloquear o app por alguns segundos e depois irá liberá-lo caso eu queira usar mais."
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    LimitOption(
                        selected = selectedType == LimitType.HARD_BLOCK_WITH_PASSWORD,
                        onSelect = { selectedType = LimitType.HARD_BLOCK_WITH_PASSWORD },
                        text = "Desejo que o app seja bloqueado porém quero poder desbloqueá-lo com senha quando o tempo acabar."
                    )
                }
            }

            if (requiresPassword) {
                Spacer(modifier = Modifier.height(20.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = "Bloqueio com senha",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Caso seja bloqueio por senha, é possível criar uma senha, ou usar senhas existentes. Se caso não tiver senhas, terá obrigatoriamente que criar uma.",
                            color = TextSecondary
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = if (hasExistingPassword) "Senha existente encontrada." else "Nenhuma senha encontrada. Ao salvar, você será levado para criar uma senha.",
                            color = if (hasExistingPassword) AccentCyan else DangerRed,
                            fontWeight = FontWeight.Medium
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = onCreateOrManagePassword,
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(text = if (hasExistingPassword) "Gerenciar senha" else "Criar senha", color = DarkBg)
                        }
                    }
                }
            }

            if (requiresDays) {
                Spacer(modifier = Modifier.height(20.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = "Bloqueio sem senha",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Caso seja bloqueio sem senha, eu devo definir quantos dias o bloqueio deve durar, no máximo 120 dias.",
                            color = TextSecondary
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = daysToBlockText,
                            onValueChange = { value ->
                                val digits = value.filter { it.isDigit() }
                                val parsed = digits.toIntOrNull()?.coerceIn(0, 120)
                                daysToBlockText = parsed?.toString() ?: digits
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Quantidade de dias (máx. 120)", color = TextHint) },
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Surface(
                            color = DangerRed.copy(alpha = 0.10f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Com um aviso, no qual diz que:",
                                    color = DangerRed,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Caso concorde será impossível utilizar o app mais do que o especificado diariamente e o bloqueio durará até os dias determinados acabarem, sendo impossível burlar.",
                                    color = DangerRed
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "Escreva abaixo: eu concordo e entendo os riscos.",
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = agreementText,
                            onValueChange = { agreementText = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("eu concordo e entendo os riscos", color = TextHint) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancelar", color = TextSecondary)
                }

                Button(
                    onClick = {
                        onSave(
                            BlockConfig(
                                dailyLimitHours = dailyLimitHours,
                                daysLimit = if (requiresDays) daysToBlock else 0,
                                usePassword = selectedType == LimitType.HARD_BLOCK_WITH_PASSWORD,
                                limitType = selectedType,
                                agreementText = agreementText.trim()
                            )
                        )
                    },
                    enabled = canSave,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Salvar", color = DarkBg, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun LimitOption(
    selected: Boolean,
    onSelect: () -> Unit,
    text: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect),
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(
            text = text,
            color = TextPrimary,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}
