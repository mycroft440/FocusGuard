package com.focusguard.ui.compose.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockReset
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.focusguard.R
import com.focusguard.security.DeactivationCredentialManager
import com.focusguard.security.MasterCredentialConfigurationManager
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.AccentCyanInk
import com.focusguard.ui.compose.theme.AccentCyanLine
import com.focusguard.ui.compose.theme.AccentIconBadge
import com.focusguard.ui.compose.theme.CardBorder
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.SuccessGreen
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.ui.compose.theme.WarningAmber
import com.focusguard.ui.compose.theme.accentWashBrush
import com.focusguard.ui.compose.theme.focusCardBrush
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val DialogShape = RoundedCornerShape(28.dp)
private val FieldShape = RoundedCornerShape(14.dp)
private val PanelShape = RoundedCornerShape(14.dp)
private val ActionShape = RoundedCornerShape(14.dp)

/** O que o formulário recusou, para marcar o campo certo além de mostrar a mensagem. */
private enum class CredentialFormError {
    INVALID_CURRENT,
    TOO_SHORT,
    MISMATCH,
    SAVE_FAILED
}

/**
 * Criação e troca da senha mestra, e a entrega do código de recuperação.
 *
 * O painel segue o resto do app — cartão escuro em degradê com halo do acento
 * no topo, selo de ícone e botões cheios — em vez do AlertDialog padrão, que
 * caía no cinza-arroxeado do Material e destoava de todas as outras telas.
 */
@Composable
internal fun DeactivationCredentialDialog(
    managementLocked: Boolean,
    configureCredential: (suspend (String) -> String)? = null,
    onDismiss: () -> Unit,
    onCredentialChanged: () -> Unit
) {
    val context = LocalContext.current
    val manager = remember(context) { DeactivationCredentialManager(context) }
    val fallbackConfigurationManager = remember(context) {
        MasterCredentialConfigurationManager(context)
    }
    val credentialWriter: suspend (String) -> String = configureCredential ?: { password ->
        fallbackConfigurationManager.configure(password)
    }
    val coroutineScope = rememberCoroutineScope()
    var wasConfigured by remember { mutableStateOf(manager.hasCredential()) }
    var currentCredential by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf<CredentialFormError?>(null) }
    var recoveryCode by remember { mutableStateOf<String?>(null) }
    var blockedDuringSave by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    val effectiveManagementLocked = managementLocked || blockedDuringSave

    val canSubmit = !isSaving && newPassword.isNotBlank() && confirmation.isNotBlank()

    fun save() {
        // Lê o estado ao vivo, não o [canSubmit] desta composição: um segundo
        // toque antes da recomposição não pode disparar outro salvamento.
        if (isSaving || newPassword.isBlank() || confirmation.isBlank()) return
        if (!DeactivationCredentialManager.isPasswordValid(newPassword)) {
            formError = CredentialFormError.TOO_SHORT
            return
        }
        if (newPassword != confirmation) {
            formError = CredentialFormError.MISMATCH
            return
        }
        if (wasConfigured) {
            when (manager.verify(currentCredential)) {
                DeactivationCredentialManager.VerificationResult.PASSWORD_ACCEPTED,
                DeactivationCredentialManager.VerificationResult.RECOVERY_ACCEPTED -> Unit
                DeactivationCredentialManager.VerificationResult.NOT_CONFIGURED -> {
                    wasConfigured = false
                }
                DeactivationCredentialManager.VerificationResult.REJECTED -> {
                    formError = CredentialFormError.INVALID_CURRENT
                    return
                }
            }
        }

        isSaving = true
        coroutineScope.launch {
            try {
                recoveryCode = credentialWriter(newPassword)
            } catch (
                error: MasterCredentialConfigurationManager.ConfigurationBlockedException
            ) {
                blockedDuringSave = true
                formError = null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                formError = CredentialFormError.SAVE_FAILED
            } finally {
                isSaving = false
            }
        }
    }

    val code = recoveryCode
    val headerIcon: ImageVector
    val headerAccent: Color
    val headerTitle: String
    when {
        effectiveManagementLocked -> {
            headerIcon = Icons.Outlined.Lock
            headerAccent = WarningAmber
            headerTitle = stringResource(R.string.deactivation_password_title)
        }
        code != null -> {
            headerIcon = Icons.Outlined.VerifiedUser
            headerAccent = SuccessGreen
            headerTitle = stringResource(R.string.deactivation_recovery_title)
        }
        wasConfigured -> {
            headerIcon = Icons.Outlined.LockReset
            headerAccent = AccentCyan
            headerTitle = stringResource(R.string.deactivation_password_change_title)
        }
        else -> {
            headerIcon = Icons.Outlined.Key
            headerAccent = AccentCyan
            headerTitle = stringResource(R.string.deactivation_password_create_title)
        }
    }

    // Uma janela só para os três estados: trocar de ramo com um Dialog em cada
    // um fecharia e reabriria a janela ao salvar, e o painel piscaria.
    CredentialDialogFrame(
        icon = headerIcon,
        accent = headerAccent,
        title = headerTitle,
        onDismissRequest = {
            if (recoveryCode == null) onDismiss()
        }
    ) {
        when {
            effectiveManagementLocked -> {
                Text(
                    text = stringResource(R.string.deactivation_password_locked_description),
                    color = TextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(24.dp))
                SecondaryAction(
                    text = stringResource(R.string.status_close),
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            code != null -> {
                Text(
                    text = stringResource(R.string.deactivation_recovery_description),
                    color = TextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(20.dp))
                RecoveryCodePanel(code)
                Spacer(Modifier.height(14.dp))
                NoticePanel(
                    icon = Icons.Outlined.WarningAmber,
                    accent = WarningAmber,
                    text = stringResource(R.string.deactivation_recovery_warning)
                )
                Spacer(Modifier.height(24.dp))
                PrimaryAction(
                    text = stringResource(R.string.deactivation_recovery_saved_action),
                    onClick = {
                        onCredentialChanged()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            else -> {
                NoticePanel(
                    icon = Icons.Outlined.Info,
                    accent = AccentCyan,
                    text = stringResource(R.string.deactivation_password_description)
                )
                Spacer(Modifier.height(20.dp))

                if (wasConfigured) {
                    MasterPasswordField(
                        value = currentCredential,
                        onValueChange = {
                            currentCredential = it
                            formError = null
                        },
                        label = stringResource(R.string.deactivation_password_current_label),
                        leadingIcon = Icons.Outlined.Key,
                        isError = formError == CredentialFormError.INVALID_CURRENT,
                        imeAction = ImeAction.Next
                    )
                    Spacer(Modifier.height(12.dp))
                }

                MasterPasswordField(
                    value = newPassword,
                    onValueChange = {
                        newPassword = it
                        formError = null
                    },
                    label = stringResource(R.string.deactivation_password_new_label),
                    leadingIcon = Icons.Outlined.Lock,
                    isError = formError == CredentialFormError.TOO_SHORT,
                    imeAction = ImeAction.Next
                )
                Spacer(Modifier.height(8.dp))
                LengthRequirement(
                    met = DeactivationCredentialManager.isPasswordValid(newPassword),
                    failed = formError == CredentialFormError.TOO_SHORT
                )
                Spacer(Modifier.height(12.dp))

                val confirmationMatches = confirmation.isNotEmpty() &&
                    confirmation == newPassword &&
                    DeactivationCredentialManager.isPasswordValid(newPassword)
                MasterPasswordField(
                    value = confirmation,
                    onValueChange = {
                        confirmation = it
                        formError = null
                    },
                    label = stringResource(R.string.deactivation_password_confirm_label),
                    leadingIcon = if (confirmationMatches) {
                        Icons.Filled.CheckCircle
                    } else {
                        Icons.Outlined.Lock
                    },
                    leadingTint = if (confirmationMatches) SuccessGreen else null,
                    isError = formError == CredentialFormError.MISMATCH,
                    imeAction = ImeAction.Done,
                    onImeAction = ::save
                )

                val errorText = when (formError) {
                    CredentialFormError.INVALID_CURRENT ->
                        stringResource(R.string.deactivation_password_invalid)
                    CredentialFormError.MISMATCH ->
                        stringResource(R.string.deactivation_passwords_differ)
                    CredentialFormError.SAVE_FAILED ->
                        stringResource(R.string.deactivation_password_save_failed)
                    // O mínimo de caracteres já fica vermelho logo abaixo do campo.
                    CredentialFormError.TOO_SHORT, null -> null
                }
                errorText?.let {
                    Spacer(Modifier.height(14.dp))
                    NoticePanel(
                        icon = Icons.Outlined.ErrorOutline,
                        accent = DangerRed,
                        text = it
                    )
                }

                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SecondaryAction(
                        text = stringResource(R.string.cancel),
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                    PrimaryAction(
                        text = stringResource(R.string.deactivation_password_save_action),
                        onClick = ::save,
                        enabled = canSubmit,
                        loading = isSaving,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * Moldura comum aos três estados: cartão do app, halo do acento no topo e o
 * cabeçalho centralizado com selo e título.
 *
 * O conteúdo rola porque, com o teclado aberto e o texto de apoio longo, o
 * painel não cabe inteiro em telas baixas.
 */
@Composable
private fun CredentialDialogFrame(
    icon: ImageVector,
    accent: Color,
    title: String,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val glow = remember(accent) {
        Brush.verticalGradient(
            0f to accent.copy(alpha = 0.10f),
            0.35f to Color.Transparent
        )
    }
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .animateContentSize()
                .clip(DialogShape)
                .background(focusCardBrush())
                .background(glow)
                .border(BorderStroke(1.dp, accent.copy(alpha = 0.22f)), DialogShape)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
            ) {
                AccentIconBadge(
                    icon = icon,
                    accent = accent,
                    size = 64.dp,
                    iconSize = 30.dp,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.3).sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                content()
            }
        }
    }
}

/** Painel recuado com ícone, para a explicação, o aviso e o erro. */
@Composable
private fun NoticePanel(
    icon: ImageVector,
    accent: Color,
    text: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(accent.copy(alpha = 0.07f))
            .border(1.dp, accent.copy(alpha = 0.20f), PanelShape)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            color = TextSecondary,
            fontSize = 13.sp,
            lineHeight = 19.sp
        )
    }
}

@Composable
private fun MasterPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    leadingIcon: ImageVector,
    isError: Boolean,
    imeAction: ImeAction,
    leadingTint: Color? = null,
    onImeAction: () -> Unit = {}
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        leadingIcon = {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = leadingTint ?: LocalContentColor.current,
                modifier = Modifier.size(20.dp)
            )
        },
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = stringResource(
                        if (visible) R.string.auth_hide_password else R.string.auth_show_password
                    ),
                    modifier = Modifier.size(20.dp)
                )
            }
        },
        isError = isError,
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = imeAction
        ),
        keyboardActions = KeyboardActions(onDone = { onImeAction() }),
        shape = FieldShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            errorTextColor = TextPrimary,
            // Sem preenchimento: o rótulo que sobe para a borda ficaria com um
            // retângulo do fundo do campo atrás dele, por cima do degradê do cartão.
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            errorContainerColor = Color.Transparent,
            cursorColor = AccentCyan,
            errorCursorColor = DangerRed,
            focusedBorderColor = AccentCyan,
            unfocusedBorderColor = CardBorder,
            errorBorderColor = DangerRed,
            focusedLabelColor = AccentCyan,
            unfocusedLabelColor = TextHint,
            errorLabelColor = DangerRed,
            focusedLeadingIconColor = AccentCyan,
            unfocusedLeadingIconColor = TextHint,
            errorLeadingIconColor = DangerRed,
            focusedTrailingIconColor = TextSecondary,
            unfocusedTrailingIconColor = TextHint,
            errorTrailingIconColor = TextSecondary
        )
    )
}

/**
 * O mínimo de caracteres como requisito vivo: cinza enquanto falta, verde
 * quando atendido e vermelho se a pessoa tentou salvar sem cumprir.
 */
@Composable
private fun LengthRequirement(met: Boolean, failed: Boolean) {
    val color = when {
        met -> SuccessGreen
        failed -> DangerRed
        else -> TextHint
    }
    Row(
        modifier = Modifier.padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = when {
                met -> Icons.Filled.CheckCircle
                failed -> Icons.Outlined.ErrorOutline
                else -> Icons.Outlined.RadioButtonUnchecked
            },
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = stringResource(
                R.string.deactivation_password_too_short,
                DeactivationCredentialManager.MIN_PASSWORD_LENGTH
            ),
            color = color,
            fontSize = 12.sp
        )
    }
}

/** O código em destaque, monoespaçado para os quatro grupos ficarem alinhados e fáceis de copiar à mão. */
@Composable
private fun RecoveryCodePanel(code: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(accentWashBrush(AccentCyan))
            .border(1.dp, AccentCyanLine, PanelShape)
            .padding(horizontal = 12.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        SelectionContainer {
            Text(
                text = code,
                color = TextPrimary,
                fontSize = 18.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun PrimaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(50.dp),
        shape = ActionShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = AccentCyan,
            contentColor = AccentCyanInk,
            disabledContainerColor = AccentCyan.copy(alpha = 0.22f),
            disabledContentColor = TextPrimary.copy(alpha = 0.45f)
        )
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = AccentCyan,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Text(text, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun SecondaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(50.dp),
        shape = ActionShape,
        border = BorderStroke(1.dp, CardBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
    ) {
        Text(text, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
