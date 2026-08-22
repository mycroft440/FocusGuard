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
import com.focusguard.contract.EnforcementUiContract
import kotlin.OptIn
import com.focusguard.security.AuthManager
import com.focusguard.permissions.ProtectionPermissionGate
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.ui.PermissionsActivity
import com.focusguard.ui.compose.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LimitsSecurityScreen(
    authManager: AuthManager,
    deviceOwnerManager: DeviceOwnerManager,
    blockingSessionManager: BlockingSessionManager,
    protectionPermissionGate: ProtectionPermissionGate,
    onBack: () -> Unit
) {
    val context = LocalContext.current
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
                            text = stringResource(R.string.limits_intrusion_security_title),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = stringResource(R.string.limits_intrusion_security_description),
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
                                    if (!protectionPermissionGate.read().isReady) {
                                        context.startActivity(
                                            PermissionsActivity.createPendingProtectionIntent(
                                                context
                                            )
                                        )
                                        return@Switch
                                    }
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
                        EnforcementUiContract.ACTION_REFRESH_BLOCKING
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
