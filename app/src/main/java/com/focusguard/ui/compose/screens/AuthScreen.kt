package com.focusguard.ui.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
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
    var errorMessage by remember { mutableStateOf("") }
    
    // CameraManager singleton para evitar race conditions de instâncias múltiplas
    val cameraManager = remember { CameraManager(activity) }

    // Tenta biometria automaticamente
    LaunchedEffect(Unit) {
        if (authManager.hasPasswordSet()) {
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
            .background(DarkBg)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = "Bloqueado",
            tint = AccentCyan,
            modifier = Modifier.size(64.dp)
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "App Bloqueado",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Digite sua senha para acessar o FocusGuard",
            fontSize = 14.sp,
            color = TextSecondary
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        OutlinedTextField(
            value = passwordInput,
            onValueChange = { passwordInput = it },
            label = { Text("Senha", color = TextHint) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            colors = TextFieldDefaults.outlinedTextFieldColors(
                focusedBorderColor = AccentCyan,
                unfocusedBorderColor = CardBorder,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
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
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = {
                if (authManager.verifyPassword(passwordInput)) {
                    onUnlock()
                } else {
                    val failed = authManager.incrementFailedAttempts()
                    val limit = authManager.getMaxPasswordAttempts()
                    
                    if (limit > 0 && failed >= limit) {
                        errorMessage = "Senha incorreta! Limite de tentativas excedido."
                        if (authManager.isPhotoCaptureEnabled()) {
                            // Reutiliza a mesma instância de CameraManager para evitar race conditions
                            cameraManager.setupAndCaptureSilent(activity) { _ ->
                                // Photo saved silently.
                            }
                        }
                    } else if (limit > 0) {
                        errorMessage = "Senha incorreta. Tentativa $failed de $limit"
                    } else {
                        errorMessage = "Senha incorreta."
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Desbloquear", fontWeight = FontWeight.Bold, color = DarkBg)
        }
        
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
            Text("Usar Biometria")
        }
    }
}
