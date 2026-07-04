package com.focusguard.ui.compose.screens

import kotlin.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch
import com.focusguard.R
import com.focusguard.security.AuthManager
import com.focusguard.security.CameraManager
import com.focusguard.ui.compose.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    authManager: AuthManager,
    activity: FragmentActivity,
    onUnlock: () -> Unit
) {
    var passwordInput by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val cameraManager = remember { CameraManager(activity) }
    val biometricsAvailable = remember { authManager.isBiometricAvailable() }

    // [L1] Shake animation state
    var shakeOffset by remember { mutableStateOf(0f) }
    val animatedShakeOffset by animateFloatAsState(
        targetValue = shakeOffset,
        animationSpec = spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMedium)
    )

    LaunchedEffect(shakeOffset) {
        if (shakeOffset != 0f) {
            kotlinx.coroutines.delay(100)
            shakeOffset = 0f
        }
    }

    val handleUnlock = {
        scope.launch {
            try {
                if (authManager.verifyPassword(passwordInput)) {
                    onUnlock()
                } else {
                    shakeOffset = 20f // [L1] Trigger shake
                    val failed = authManager.incrementFailedAttempts()
                    val limit = authManager.getMaxPasswordAttempts()

                    errorMessage = when {
                        limit > 0 && failed >= limit -> {
                            if (authManager.isPhotoCaptureEnabled()) {
                                cameraManager.setupAndCaptureSilent(activity) { _ -> }
                            }
                            activity.getString(R.string.auth_wrong_password_limit)
                        }
                        limit > 0 -> activity.getString(R.string.auth_wrong_password_attempt, failed, limit)
                        else -> activity.getString(R.string.auth_wrong_password)
                    }
                }
            } catch (e: Throwable) {
                // Captura Throwable — em release builds com R8, podem ocorrer
                // Errors que escapam de catch(Exception) e crasham o app na
                // tela de unlock, impedindo o usuário de acessar o app.
                errorMessage = activity.getString(R.string.auth_wrong_password)
            }
        }
    }

    LaunchedEffect(biometricsAvailable) {
        if (biometricsAvailable && authManager.hasPasswordSet()) {
            authManager.showBiometricPrompt(
                activity = activity,
                onSuccess = onUnlock,
                onError = { msg -> errorMessage = msg }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(24.dp)
            .offset(x = animatedShakeOffset.dp), // [L1] Apply shake
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(1f, fill = false).heightIn(min = 48.dp))

        // [L1] Infinite pulse for lock icon
        val infiniteTransition = rememberInfiniteTransition()
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            )
        )

        Box(
            modifier = Modifier
                .size(100.dp)
                .graphicsLayer {
                    scaleX = pulseScale
                    scaleY = pulseScale
                }
                .clip(RoundedCornerShape(30.dp))
                .background(AccentCyan.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = stringResource(R.string.auth_locked_content_description),
                tint = AccentCyan,
                modifier = Modifier.size(56.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.auth_locked_title),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.auth_locked_description),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = passwordInput,
            onValueChange = { passwordInput = it },
            label = { Text(stringResource(R.string.auth_password_label)) },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                val image = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff
                val description = if (passwordVisible) R.string.auth_hide_password else R.string.auth_show_password
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(imageVector = image, contentDescription = stringResource(description), tint = TextHint)
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { handleUnlock() }),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentCyan,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
            ),
            modifier = Modifier.fillMaxWidth()
        )

        if (errorMessage.isNotEmpty()) {
            Text(
                text = errorMessage,
                color = DangerRed,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = { handleUnlock() },
            enabled = passwordInput.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(stringResource(R.string.auth_unlock_button), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.background)
        }

        if (biometricsAvailable) {
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = {
                    authManager.showBiometricPrompt(
                        activity = activity,
                        onSuccess = onUnlock,
                        onError = { msg -> errorMessage = msg }
                    )
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan),
                border = androidx.compose.foundation.BorderStroke(1.dp, AccentCyan),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Fingerprint, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.auth_biometrics_button))
            }
        }

        Spacer(modifier = Modifier.weight(1f, fill = false).heightIn(min = 24.dp))
    }
}