package com.focusguard.ui.compose.screens

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Security
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import kotlin.OptIn
import com.focusguard.security.AuthManager
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.ui.compose.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LimitsSecurityScreen(authManager: AuthManager, onBack: () -> Unit) {
    val context = LocalContext.current
    val deviceOwnerManager = remember { DeviceOwnerManager.getInstance(context) }
    val blockingSessionManager = remember { BlockingSessionManager.getInstance(context) }
    val policyScope = rememberCoroutineScope()
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
            Toast.makeText(context, context.getString(R.string.limits_hidden_camera_active), Toast.LENGTH_SHORT).show()
        } else {
            photoEnabled = false
            authManager.setPhotoCaptureEnabled(false)
            Toast.makeText(context, context.getString(R.string.limits_permission_denied_feature), Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.limits_and_security), color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = saveLimitOnBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = TextPrimary)
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
            // SECTION: ANTI-INTRUSION
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                border = BorderStroke(1.dp, CardBorder)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(AccentCyan.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(22.dp))
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "Segurança Anti-Invasão",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "Configure o que acontece quando alguém erra a senha do aplicativo repetidamente.",
                        fontSize = 14.sp,
                        color = TextSecondary
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))

                    OutlinedTextField(
                        value = limitText,
                        onValueChange = { newValue ->
                            if (newValue.isEmpty() || newValue.all { it.isDigit() }) {
                                limitText = newValue
                            }
                        },
                        label = { Text(stringResource(R.string.limits_attempts_label), color = TextHint) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = Border
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.limits_intruder_selfie), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text(stringResource(R.string.limits_intruder_selfie_desc), fontSize = 12.sp, color = TextSecondary)
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

            Spacer(modifier = Modifier.height(16.dp))

            // SECTION: SAFETY MODE
            var safetyModeEnabled by remember { mutableStateOf(authManager.isSafetyModeEnabled()) }
            var showSafetyDialog by remember { mutableStateOf(false) }
            var showDeactivateDialog by remember { mutableStateOf(false) }
            var safetyInput by remember { mutableStateOf("") }
            var deactivateInput by remember { mutableStateOf("") }

            // Activation Dialog
            if (showSafetyDialog) {
                AlertDialog(
                    onDismissRequest = { showSafetyDialog = false },
                    containerColor = DarkSurface,
                    title = { Text(stringResource(R.string.limits_safety_mode_enable_title), color = DangerRed, fontWeight = FontWeight.Bold) },
                    text = {
                        Column {
                            Text(
                                stringResource(R.string.limits_safety_mode_desc),
                                color = TextSecondary, fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(stringResource(R.string.limits_safety_mode_confirm_text), color = TextPrimary, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = safetyInput,
                                onValueChange = { safetyInput = it },
                                placeholder = { Text(stringResource(R.string.limits_safety_mode_placeholder), color = TextHint) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, focusedBorderColor = DangerRed, unfocusedBorderColor = Border),
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
                        ) { Text(stringResource(R.string.sessions_confirm), color = TextPrimary) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSafetyDialog = false; safetyInput = "" }) { Text(stringResource(R.string.cancel), color = TextHint) }
                    }
                )
            }

            // [S1] Deactivation Dialog (Security Hardening)
            if (showDeactivateDialog) {
                AlertDialog(
                    onDismissRequest = { showDeactivateDialog = false },
                    containerColor = DarkSurface,
                    title = { Text("Desativar Modo Segurança?", color = TextPrimary, fontWeight = FontWeight.Bold) },
                    text = {
                        Column {
                            Text(
                                stringResource(R.string.sessions_end_desc),
                                color = TextSecondary, fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedTextField(
                                value = deactivateInput,
                                onValueChange = { deactivateInput = it },
                                placeholder = { Text("Digite sua senha", color = TextHint) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, focusedBorderColor = AccentCyan, unfocusedBorderColor = Border),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        val scope = rememberCoroutineScope()
                        Button(
                            onClick = {
                                scope.launch {
                                    if (authManager.verifyPassword(deactivateInput)) {
                                        safetyModeEnabled = false
                                        authManager.setSafetyModeEnabled(false)
                                        showDeactivateDialog = false
                                        deactivateInput = ""
                                    } else {
                                        Toast.makeText(context, context.getString(R.string.senha_incorreta), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                        ) { Text(stringResource(R.string.sessions_confirm), color = DarkBg) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeactivateDialog = false; deactivateInput = "" }) { Text(stringResource(R.string.cancel), color = TextHint) }
                    }
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                border = BorderStroke(1.dp, CardBorder)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(DangerRed.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = DangerRed, modifier = Modifier.size(22.dp))
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = stringResource(R.string.limits_security_mode),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(stringResource(R.string.limits_safety_mode_desc), fontSize = 12.sp, color = TextSecondary)
                    
                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.sessions_active_badge), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                        
                        Switch(
                            checked = safetyModeEnabled,
                            onCheckedChange = { enable ->
                                if (enable) {
                                    showSafetyDialog = true
                                } else {
                                    // [S1] Now requires authentication
                                    showDeactivateDialog = true
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = DarkBg,
                                checkedTrackColor = DangerRed
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // SECTION: DNS FILTER
            var adultFilterEnabled by remember { mutableStateOf(authManager.isAdultFilterEnabled()) }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                border = BorderStroke(1.dp, CardBorder)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(AccentCyan.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Dns, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(22.dp))
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = stringResource(R.string.limits_dns_filter_title),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(stringResource(R.string.limits_dns_filter_desc), fontSize = 12.sp, color = TextSecondary)
                    
                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.sessions_active_badge), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                        
                        Switch(
                            checked = adultFilterEnabled,
                            onCheckedChange = { enable ->
                                val maintenanceActive = deviceOwnerManager.isMaintenanceActive()
                                if (!enable && !maintenanceActive) {
                                    Toast.makeText(
                                        context,
                                        context.getString(
                                            R.string.limits_adult_filter_maintenance_required
                                        ),
                                        Toast.LENGTH_LONG
                                    ).show()
                                    return@Switch
                                }

                                if (enable) {
                                    if (!deviceOwnerManager.isDeviceOwnerActive()) {
                                        Toast.makeText(context, context.getString(R.string.limits_nuclear_required), Toast.LENGTH_LONG).show()
                                    } else {
                                        authManager.setAdultFilterEnabled(true)
                                        val success = deviceOwnerManager.enforceAdultDns()
                                        if (success) {
                                            adultFilterEnabled = true
                                            policyScope.launch {
                                                blockingSessionManager.checkAndEnforce()
                                            }
                                        } else {
                                            authManager.setAdultFilterEnabled(false)
                                            Toast.makeText(context, context.getString(R.string.limits_dns_injection_failed), Toast.LENGTH_LONG).show()
                                        }
                                    }
                                } else {
                                    authManager.setAdultFilterEnabled(false)
                                    deviceOwnerManager.clearAdultDns()
                                    adultFilterEnabled = false
                                    policyScope.launch {
                                        blockingSessionManager.checkAndEnforce()
                                    }
                                }
                                context.sendBroadcast(
                                    android.content.Intent(
                                        com.focusguard.service.BlockingAccessibilityService.ACTION_REFRESH_BLOCKING
                                    ).setPackage(context.packageName)
                                )
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
    }
}
