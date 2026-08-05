package com.focusguard.ui.compose.screens

import androidx.compose.runtime.*
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.focusguard.R
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
                        text = stringResource(R.string.usage_block_daily_limit_question),
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = dailyLimitHoursText,
                        onValueChange = { value -> dailyLimitHoursText = value.filter { it.isDigit() } },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.ex_2), color = TextHint) },
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Surface(
                        color = DangerRed.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.usage_block_daily_limit_warning),
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
                        text = stringResource(R.string.usage_block_type_question),
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    LimitOption(
                        selected = selectedType == LimitType.HARD_BLOCK_NO_PASSWORD,
                        onSelect = { selectedType = LimitType.HARD_BLOCK_NO_PASSWORD },
                        text = stringResource(R.string.usage_block_type_hard_no_password)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    LimitOption(
                        selected = selectedType == LimitType.WARNING_ONLY,
                        onSelect = { selectedType = LimitType.WARNING_ONLY },
                        text = stringResource(R.string.usage_block_type_warning_only)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    LimitOption(
                        selected = selectedType == LimitType.HARD_BLOCK_WITH_PASSWORD,
                        onSelect = { selectedType = LimitType.HARD_BLOCK_WITH_PASSWORD },
                        text = stringResource(R.string.usage_block_type_hard_with_password)
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
                            text = stringResource(R.string.usage_block_password_title),
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = stringResource(R.string.usage_block_password_description),
                            color = TextSecondary
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = if (hasExistingPassword) {
                                stringResource(R.string.final_config_existing_password)
                            } else {
                                stringResource(R.string.usage_block_password_missing)
                            },
                            color = if (hasExistingPassword) AccentCyan else DangerRed,
                            fontWeight = FontWeight.Medium
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = onCreateOrManagePassword,
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(
                                text = if (hasExistingPassword) {
                                    stringResource(R.string.usage_block_manage_password)
                                } else {
                                    stringResource(R.string.criar_senha)
                                },
                                color = DarkBg
                            )
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
                            text = stringResource(R.string.usage_block_no_password_title),
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = stringResource(R.string.usage_block_no_password_description),
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
                            placeholder = {
                                Text(
                                    stringResource(R.string.quantidade_de_dias_max_120),
                                    color = TextHint
                                )
                            },
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Surface(
                            color = DangerRed.copy(alpha = 0.10f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = stringResource(R.string.usage_block_warning_heading),
                                    color = DangerRed,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = stringResource(R.string.usage_block_irreversible_warning),
                                    color = DangerRed
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = stringResource(R.string.usage_block_agreement_instruction),
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = agreementText,
                            onValueChange = { agreementText = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text(
                                    stringResource(R.string.eu_concordo_e_entendo_os_riscos),
                                    color = TextHint
                                )
                            }
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
                    Text(stringResource(R.string.cancel), color = TextSecondary)
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
                    Text(stringResource(R.string.save), color = DarkBg, fontWeight = FontWeight.Bold)
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