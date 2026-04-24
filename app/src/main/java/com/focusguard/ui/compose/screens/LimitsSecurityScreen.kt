package com.focusguard.ui.compose.screens

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.security.AuthManager
import com.focusguard.ui.compose.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LimitsSecurityScreen(authManager: AuthManager, onBack: () -> Unit) {
    val context = LocalContext.current
    var limitText by remember { mutableStateOf(authManager.getMaxPasswordAttempts().toString()) }
    var photoEnabled by remember { mutableStateOf(authManager.isPhotoCaptureEnabled()) }

    // Save only when the user leaves the screen (via onBack)
    val saveLimitOnBack = {
        val num = limitText.toIntOrNull() ?: 0
        authManager.setMaxPasswordAttempts(num)
        onBack()
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            photoEnabled = true
            authManager.setPhotoCaptureEnabled(true)
            Toast.makeText(context, "Câmera oculta ativada", Toast.LENGTH_SHORT).show()
        } else {
            photoEnabled = false
            authManager.setPhotoCaptureEnabled(false)
            Toast.makeText(context, "Permissão negada. Recurso desativado.", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Limites e Segurança", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = saveLimitOnBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        },
        containerColor = DarkBg
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp)
        ) {
            Text(
                text = "Segurança Anti-Invasão",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Configure o que acontece quando alguém erra a senha do aplicativo repetidamente.",
                fontSize = 14.sp,
                color = TextSecondary
            )
            
            Spacer(modifier = Modifier.height(32.dp))

            OutlinedTextField(
                value = limitText,
                onValueChange = { newValue ->
                    // Only accept numeric input
                    if (newValue.isEmpty() || newValue.all { it.isDigit() }) {
                        limitText = newValue
                    }
                },
                label = { Text("Limite de Tentativas de Senha (0 para infinito)", color = TextHint) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = AccentCyan
                ),
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Selfie do Intruso", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text("Tira uma foto invisível com a câmera frontal quando o limite for excedido.", fontSize = 12.sp, color = TextSecondary)
                }
                
                Switch(
                    checked = photoEnabled,
                    onCheckedChange = { enable ->
                        if (enable) {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        } else {
                            photoEnabled = false
                            authManager.setPhotoCaptureEnabled(false)
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = DarkBg,
                        checkedTrackColor = AccentCyan
                    )
                )
            }
        }
    }
}
