package com.focusguard.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.focusguard.R
import com.focusguard.focusmode.FocusModeManager
import com.focusguard.focusmode.FocusModeStore
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.AuthManager
import com.focusguard.security.DeactivationCredentialManager
import com.focusguard.security.PasswordAppUnlockStore
import com.focusguard.security.PasswordTargetAccessGrant
import com.focusguard.security.RemoveAllBlocksAuthorizationPolicy
import com.focusguard.security.RemoveAllBlocksAuthorizationPolicy.Gate
import com.focusguard.security.RemoveAllBlocksAuthorizationPolicy.Summary
import com.focusguard.ui.compose.screens.BlockTypeUi
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.CardBorder
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.DarkCard
import com.focusguard.ui.compose.theme.FocusGuardTheme
import com.focusguard.ui.compose.theme.SuccessGreen
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.ui.compose.theme.WarningAmber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Operational surface that clears every configured blocking source.
 *
 * Flow: the dialog lists what is active ("2 bloqueios por senha", "3 bloqueios
 * por tempo sem senha"…) and asks for confirmation. When nothing is active it
 * just says so. Password blocks alone are removed after the confirmation; any
 * other protection also requires the master credential — see
 * [RemoveAllBlocksAuthorizationPolicy].
 */
class RemoveAllBlocksActivity : ComponentActivity() {

    private enum class Step { LOADING, NOTHING, CONFIRM, MASTER, MASTER_MISSING }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FocusGuardTheme {
                val scope = rememberCoroutineScope()
                val credentialManager = remember {
                    DeactivationCredentialManager(applicationContext)
                }
                val blockingManager = remember {
                    BlockingSessionManager.getInstance(applicationContext)
                }
                val focusModeManager = remember {
                    FocusModeManager.getInstance(applicationContext)
                }
                val targetCredentialStore = remember {
                    PasswordAppUnlockStore(applicationContext)
                }

                var step by remember { mutableStateOf(Step.LOADING) }
                var summary by remember { mutableStateOf(Summary()) }
                var credential by remember { mutableStateOf("") }
                var error by remember { mutableStateOf<String?>(null) }
                var working by remember { mutableStateOf(false) }
                val masterSetupLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) {
                    credential = ""
                    error = null
                    step = if (credentialManager.hasCredential()) {
                        Step.MASTER
                    } else {
                        Step.MASTER_MISSING
                    }
                }

                suspend fun readSummary(): Summary = withContext(Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    val overview = blockingManager.getBlockOverview()
                    Summary(
                        passwordBlocks = overview.passwordEntries.size,
                        dailyLimits = overview.dailyLimitEntries.size,
                        scheduledPeriods = overview.scheduledTimeEntries.size,
                        timeBlocks = overview.dopamineFastEntries.size,
                        adultFilterActive = AuthManager.isAdultFilterConfigured(
                            applicationContext
                        ),
                        focusModeActive = FocusModeStore.isActive(applicationContext, now)
                    )
                }

                fun removeAllBlocks() {
                    if (working) return
                    working = true
                    error = null
                    scope.launch {
                        try {
                            val removed = withContext(Dispatchers.IO) {
                                focusModeManager.forceStopForDevelopmentExit()
                                PasswordTargetAccessGrant.clear()
                                val blocksRemoved = blockingManager
                                    .removeAllBlocksForDevelopmentExit()
                                if (blocksRemoved) {
                                    targetCredentialStore.clearAll()
                                    blockingManager.checkAndEnforce()
                                }
                                blocksRemoved
                            }
                            if (removed) {
                                Toast.makeText(
                                    this@RemoveAllBlocksActivity,
                                    getString(R.string.master_remove_all_blocks_success),
                                    Toast.LENGTH_LONG
                                ).show()
                                finish()
                            } else {
                                error = getString(R.string.master_remove_all_blocks_failed)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            error = getString(R.string.master_remove_all_blocks_failed)
                        } finally {
                            working = false
                        }
                    }
                }

                /** "Sim": re-reads what is active so nothing added meanwhile slips by. */
                fun onConfirmed() {
                    if (working) return
                    scope.launch {
                        val latest = runCatching { readSummary() }.getOrNull()
                        if (latest == null) {
                            error = getString(R.string.master_remove_all_blocks_failed)
                            return@launch
                        }
                        summary = latest
                        when (RemoveAllBlocksAuthorizationPolicy.evaluate(latest)) {
                            Gate.NOTHING_TO_REMOVE -> step = Step.NOTHING
                            Gate.CONFIRM_ONLY -> removeAllBlocks()
                            Gate.REQUIRE_MASTER_CREDENTIAL -> {
                                credential = ""
                                error = null
                                step = if (credentialManager.hasCredential()) {
                                    Step.MASTER
                                } else {
                                    Step.MASTER_MISSING
                                }
                            }
                        }
                    }
                }

                fun verifyMasterAndRemove() {
                    when (credentialManager.verify(credential)) {
                        DeactivationCredentialManager.VerificationResult.PASSWORD_ACCEPTED,
                        DeactivationCredentialManager.VerificationResult.RECOVERY_ACCEPTED -> {
                            credential = ""
                            removeAllBlocks()
                        }
                        DeactivationCredentialManager.VerificationResult.REJECTED -> {
                            error = getString(R.string.master_credential_wrong)
                        }
                        DeactivationCredentialManager.VerificationResult.NOT_CONFIGURED -> {
                            step = Step.MASTER_MISSING
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    val initial = runCatching { readSummary() }.getOrNull()
                    if (initial == null) {
                        // Sem conseguir ler o estado, trate como protegido.
                        step = Step.CONFIRM
                        return@LaunchedEffect
                    }
                    summary = initial
                    step = if (initial.isEmpty) Step.NOTHING else Step.CONFIRM
                }

                RemoveAllBlocksDialogFrame(onDismiss = { if (!working) finish() }) {
                    AnimatedContent(
                        targetState = step,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "RemoveAllBlocksStep"
                    ) { current ->
                        when (current) {
                            Step.LOADING -> LoadingContent()
                            Step.NOTHING -> NothingActiveContent(onClose = { finish() })
                            Step.CONFIRM -> ConfirmContent(
                                summary = summary,
                                working = working,
                                error = error,
                                onCancel = { finish() },
                                onConfirm = ::onConfirmed
                            )
                            Step.MASTER -> MasterCredentialContent(
                                credential = credential,
                                working = working,
                                error = error,
                                onCredentialChange = {
                                    credential = it
                                    error = null
                                },
                                onCancel = { finish() },
                                onConfirm = ::verifyMasterAndRemove
                            )
                            Step.MASTER_MISSING -> MasterMissingContent(
                                onCancel = { finish() },
                                onCreate = {
                                    masterSetupLauncher.launch(
                                        MasterPasswordActivity.createIntent(
                                            this@RemoveAllBlocksActivity
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoveAllBlocksDialogFrame(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(DarkCard)
                .border(BorderStroke(1.dp, CardBorder), RoundedCornerShape(28.dp))
                .padding(24.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun DialogHeader(icon: ImageVector, tint: Color, title: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.14f))
                .border(BorderStroke(1.dp, tint.copy(alpha = 0.35f)), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            title,
            color = TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun LoadingContent() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(color = AccentCyan, strokeWidth = 3.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.master_remove_all_blocks_title),
            color = TextSecondary,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun NothingActiveContent(onClose: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        DialogHeader(
            icon = Icons.Outlined.CheckCircle,
            tint = SuccessGreen,
            title = stringResource(R.string.remove_all_blocks_none_title)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.remove_all_blocks_none_message),
            color = TextSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AccentCyan,
                contentColor = DarkBg
            )
        ) {
            Text(stringResource(R.string.action_ok), fontWeight = FontWeight.Bold)
        }
    }
}

private data class SummaryLine(val text: String, val icon: ImageVector, val accent: Color)

@Composable
private fun summaryLines(summary: Summary): List<SummaryLine> = buildList {
    if (summary.passwordBlocks > 0) add(
        SummaryLine(
            pluralStringResource(
                R.plurals.remove_all_blocks_password_count,
                summary.passwordBlocks,
                summary.passwordBlocks
            ),
            BlockTypeUi.PASSWORD.icon,
            BlockTypeUi.PASSWORD.accent
        )
    )
    if (summary.dailyLimits > 0) add(
        SummaryLine(
            pluralStringResource(
                R.plurals.remove_all_blocks_limit_count,
                summary.dailyLimits,
                summary.dailyLimits
            ),
            BlockTypeUi.DAILY_LIMIT.icon,
            BlockTypeUi.DAILY_LIMIT.accent
        )
    )
    if (summary.scheduledPeriods > 0) add(
        SummaryLine(
            pluralStringResource(
                R.plurals.remove_all_blocks_period_count,
                summary.scheduledPeriods,
                summary.scheduledPeriods
            ),
            BlockTypeUi.DAILY_PERIODS.icon,
            BlockTypeUi.DAILY_PERIODS.accent
        )
    )
    if (summary.timeBlocks > 0) add(
        SummaryLine(
            pluralStringResource(
                R.plurals.remove_all_blocks_time_count,
                summary.timeBlocks,
                summary.timeBlocks
            ),
            BlockTypeUi.DOPAMINE_FAST.icon,
            BlockTypeUi.DOPAMINE_FAST.accent
        )
    )
    if (summary.adultFilterActive) add(
        SummaryLine(
            stringResource(R.string.remove_all_blocks_adult_filter),
            Icons.Outlined.Block,
            DangerRed
        )
    )
    if (summary.focusModeActive) add(
        SummaryLine(
            stringResource(R.string.remove_all_blocks_focus_mode),
            Icons.Outlined.CenterFocusStrong,
            AccentCyan
        )
    )
}

@Composable
private fun ConfirmContent(
    summary: Summary,
    working: Boolean,
    error: String?,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        DialogHeader(
            icon = Icons.Outlined.DeleteSweep,
            tint = DangerRed,
            title = stringResource(R.string.master_remove_all_blocks_title)
        )
        Spacer(Modifier.height(20.dp))

        val lines = summaryLines(summary)
        if (lines.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(DarkBg)
                    .border(BorderStroke(1.dp, CardBorder), RoundedCornerShape(18.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                lines.forEach { line -> SummaryRow(line) }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.remove_all_blocks_will_be_removed),
                color = TextSecondary,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(16.dp))
        }

        Text(
            stringResource(R.string.remove_all_blocks_confirm_question),
            color = TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        ErrorText(error)
        Spacer(Modifier.height(24.dp))
        DialogButtons(
            confirmText = stringResource(R.string.remove_all_blocks_confirm_yes),
            confirmEnabled = !working,
            working = working,
            onCancel = onCancel,
            onConfirm = onConfirm
        )
    }
}

@Composable
private fun SummaryRow(line: SummaryLine) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(line.accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                line.icon,
                contentDescription = null,
                tint = line.accent,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(line.text, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MasterCredentialContent(
    credential: String,
    working: Boolean,
    error: String?,
    onCredentialChange: (String) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        DialogHeader(
            icon = Icons.Outlined.Key,
            tint = AccentCyan,
            title = stringResource(R.string.remove_all_blocks_master_title)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.remove_all_blocks_master_reason),
            color = TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = credential,
            onValueChange = onCredentialChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !working,
            isError = error != null,
            shape = RoundedCornerShape(14.dp),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            label = { Text(stringResource(R.string.master_password_settings_title)) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentCyan,
                unfocusedBorderColor = CardBorder,
                focusedLabelColor = AccentCyan,
                unfocusedLabelColor = TextHint,
                cursorColor = AccentCyan,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            )
        )
        ErrorText(error)
        Spacer(Modifier.height(24.dp))
        DialogButtons(
            confirmText = stringResource(R.string.remove_button),
            confirmEnabled = credential.isNotBlank() && !working,
            working = working,
            onCancel = onCancel,
            onConfirm = onConfirm
        )
    }
}

@Composable
private fun MasterMissingContent(onCancel: () -> Unit, onCreate: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        DialogHeader(
            icon = Icons.Outlined.Key,
            tint = WarningAmber,
            title = stringResource(R.string.remove_all_blocks_master_title)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.master_credential_not_configured),
            color = TextSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))
        DialogButtons(
            confirmText = stringResource(R.string.master_credential_create_action),
            confirmEnabled = true,
            working = false,
            confirmColor = AccentCyan,
            onCancel = onCancel,
            onConfirm = onCreate
        )
    }
}

@Composable
private fun ErrorText(error: String?) {
    if (error == null) return
    Spacer(Modifier.height(10.dp))
    Text(error, color = DangerRed, fontSize = 13.sp)
}

@Composable
private fun DialogButtons(
    confirmText: String,
    confirmEnabled: Boolean,
    working: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    confirmColor: Color = DangerRed
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = onCancel,
            enabled = !working,
            modifier = Modifier.weight(1f).height(48.dp),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, CardBorder)
        ) {
            Text(stringResource(R.string.cancel), color = TextSecondary)
        }
        Button(
            onClick = onConfirm,
            enabled = confirmEnabled,
            modifier = Modifier.weight(1f).height(48.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = confirmColor,
                contentColor = if (confirmColor == DangerRed) Color.White else DarkBg,
                disabledContainerColor = confirmColor.copy(alpha = 0.35f),
                disabledContentColor = Color.White.copy(alpha = 0.6f)
            )
        ) {
            if (working) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Color.White
                )
            } else {
                Text(
                    confirmText,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}
