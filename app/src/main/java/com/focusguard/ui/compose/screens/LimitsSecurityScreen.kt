package com.focusguard.ui.compose.screens

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.security.AuthManager
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.ui.compose.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LimitsSecurityScreen(authManager: AuthManager, onBack: () -> Unit) {
    val context = LocalContext.current
    val deviceOwnerManager = remember { DeviceOwnerManager.getInstance(context) }
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
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = AccentCyan
                ),
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
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

            Spacer(modifier = Modifier.height(32.dp))
            Divider(color = Border, thickness = 1.dp)
            Spacer(modifier = Modifier.height(32.dp))

            // SAFETY MODE
            var safetyModeEnabled by remember { mutableStateOf(authManager.isSafetyModeEnabled()) }
            var showSafetyDialog by remember { mutableStateOf(false) }
            var safetyInput by remember { mutableStateOf("") }

            if (showSafetyDialog) {
                AlertDialog(
                    onDismissRequest = { showSafetyDialog = false },
                    containerColor = DarkSurface,
                    title = { Text("Ativar Modo Segurança?", color = DangerRed, fontWeight = FontWeight.Bold) },
                    text = {
                        Column {
                            Text(
                                "Ao ativar, você NÃO poderá remover limites de uso ou sessões de tempo usando sua senha. O bloqueio será absoluto até o fim do tempo definido.",
                                color = TextSecondary, fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Para confirmar, digite 'sim' abaixo:", color = TextPrimary, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = safetyInput,
                                onValueChange = { safetyInput = it },
                                placeholder = { Text("digite sim", color = TextHint) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, focusedBorderColor = DangerRed),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (safetyInput.lowercase().trim() == "sim") {
                                    safetyModeEnabled = true
                                    authManager.setSafetyModeEnabled(true)
                                    showSafetyDialog = false
                                    safetyInput = ""
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                            enabled = safetyInput.lowercase().trim() == "sim"
                        ) { Text("Confirmar", color = TextPrimary) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSafetyDialog = false; safetyInput = "" }) { Text("Cancelar", color = TextHint) }
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Modo Segurança", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text("Impede o uso de senha para burlar limites ativos. Bloqueio total e irreversível.", fontSize = 12.sp, color = TextSecondary)
                }
                
                Switch(
                    checked = safetyModeEnabled,
                    onCheckedChange = { enable ->
                        if (enable) {
                            showSafetyDialog = true
                        } else {
                            safetyModeEnabled = false
                            authManager.setSafetyModeEnabled(false)
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = DarkBg,
                        checkedTrackColor = DangerRed
                    )
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
            Divider(color = Border, thickness = 1.dp)
            Spacer(modifier = Modifier.height(32.dp))

            // FILTRO DNS GLOBAL
            var adultFilterEnabled by remember { mutableStateOf(authManager.isAdultFilterEnabled()) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.limits_dns_filter_title), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text(stringResource(R.string.limits_dns_filter_desc), fontSize = 12.sp, color = TextSecondary)
                }
                
                Switch(
                    checked = adultFilterEnabled,
                    onCheckedChange = { enable ->
                        if (enable) {
                            if (!deviceOwnerManager.isDeviceOwnerActive()) {
                                Toast.makeText(context, "Ative a Proteção Nuclear nas configurações primeiro!", Toast.LENGTH_LONG).show()
                            } else {
                                val success = deviceOwnerManager.enforceAdultDns()
                                if (success) {
                                    adultFilterEnabled = true
                                    authManager.setAdultFilterEnabled(true)
                                    // Notifica o serviço para atualizar as URLs (24/7 block list)
                                    context.sendBroadcast(android.content.Intent(com.focusguard.service.BlockingAccessibilityService.ACTION_REFRESH_BLOCKING))
                                } else {
                                    Toast.makeText(context, "Falha ao injetar DNS. Sua versão do Android suporta?", Toast.LENGTH_LONG).show()
                                }
                            }
                        } else {
                            deviceOwnerManager.clearAdultDns()
                            adultFilterEnabled = false
                            authManager.setAdultFilterEnabled(false)
                            context.sendBroadcast(android.content.Intent(com.focusguard.service.BlockingAccessibilityService.ACTION_REFRESH_BLOCKING))
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

