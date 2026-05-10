package com.focusguard.ui.compose.screens

import kotlin.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.graphics.graphicsLayer
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import com.focusguard.security.CameraManager
import com.focusguard.security.AuthManager
import com.focusguard.ui.compose.theme.*
import com.focusguard.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.*
import androidx.fragment.app.FragmentActivity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordManagementScreen(authManager: AuthManager, onBack: () -> Unit) {
    var hasPasswords by remember { mutableStateOf(false) }
    var isAuthenticated by remember { mutableStateOf(true) } // Temporarily true to avoid flicker if no passwords
    var authInput by remember { mutableStateOf("") }
    var authError by remember { mutableStateOf("") }
    
    // Password list state
    var passwords by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val cameraManager = remember { activity?.let { CameraManager(it) } }

    // Animation states
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

    LaunchedEffect(Unit) {
        hasPasswords = authManager.hasPasswordSet()
        isAuthenticated = !hasPasswords
        passwords = authManager.getStoredPasswordLabels()
    }
    var showAddDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf<Int?>(null) }
    var showDeleteAuthDialog by remember { mutableStateOf<Int?>(null) }
    
    var tempPassword by remember { mutableStateOf("") }
    var tempPasswordLabel by remember { mutableStateOf("") }
    var actionError by remember { mutableStateOf("") }
    
    var currentAuthType by remember { mutableStateOf(authManager.getPreferredAuthType()) }

    // Add/Edit password dialog
    if (showAddDialog || showEditDialog != null) {
        val isEditing = showEditDialog != null
        var currentPasswordForEdit by remember { mutableStateOf("") }
        val context = LocalContext.current

        AlertDialog(
            onDismissRequest = { 
                showAddDialog = false
                showEditDialog = null 
                actionError = ""
                currentPasswordForEdit = ""
            },
            containerColor = DarkSurface,
            title = { Text(if (isEditing) "Editar Senha" else stringResource(R.string.create_session_new_password), color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    if (isEditing) {
                        Text(stringResource(R.string.password_mgmt_confirm_change), color = TextSecondary, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = currentPasswordForEdit,
                            onValueChange = { currentPasswordForEdit = it },
                            label = { Text(stringResource(R.string.password_mgmt_current_password), color = TextHint) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = AccentCyan
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    
                    OutlinedTextField(
                        value = tempPasswordLabel,
                        onValueChange = { tempPasswordLabel = it },
                        label = { Text(stringResource(R.string.password_mgmt_label), color = TextHint) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = AccentCyan
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = tempPassword,
                        onValueChange = { tempPassword = it },
                        label = { Text(stringResource(R.string.password_mgmt_password_numeric), color = TextHint) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = AccentCyan
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (actionError.isNotEmpty()) {
                        Text(actionError, color = DangerRed, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            },
            confirmButton = {
                val activity = LocalContext.current as? androidx.fragment.app.FragmentActivity
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isEditing && activity != null) {
                        IconButton(onClick = {
                            authManager.showBiometricPrompt(
                                activity = activity,
                                onSuccess = {
                                    scope.launch {
                                        // When using biometric to edit, we need a special method that doesn't check old password
                                        // or we can just bypass the check since biometric is strong.
                                        // For now, let's assume we need to update the password Dao directly.
                                        // I'll add a new method to AuthManager or just use a flag.
                                        // Actually, let's just use the verified state.
                                        val entries = authManager.getStoredPasswordLabels()
                                        if (showEditDialog!! in entries.indices) {
                                            // Update logic moved here
                                            val currentEntries = com.focusguard.database.AppDatabase.getDatabase(context).appPasswordDao().getAllStatic()
                                            val entry = currentEntries[showEditDialog!!]
                                            val newSalt = AuthManager.generateSalt()
                                            val newHash = AuthManager.hashPasswordWithSalt(tempPassword, newSalt)
                                            com.focusguard.database.AppDatabase.getDatabase(context).appPasswordDao().update(entry.copy(label = tempPasswordLabel, passwordHash = newHash, salt = newSalt))
                                            
                                            passwords = authManager.getStoredPasswordLabels()
                                            tempPassword = ""
                                            tempPasswordLabel = ""
                                            currentPasswordForEdit = ""
                                            actionError = ""
                                            showAddDialog = false
                                            showEditDialog = null
                                            Toast.makeText(context, context.getString(R.string.security_updated_success), Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onError = { actionError = it }
                            )
                        }) {
                            Icon(Icons.Default.Fingerprint, null, tint = AccentCyan)
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    
                    Button(
                        onClick = {
                            scope.launch {
                                if (isEditing) {
                                    if (authManager.verifyAndUpdatePasswordByIndex(showEditDialog!!, currentPasswordForEdit, tempPassword, tempPasswordLabel)) {
                                        // Success
                                    } else {
                                        actionError = "Senha atual incorreta."
                                        return@launch
                                    }
                                } else {
                                    val label = tempPasswordLabel.ifBlank { "Senha ${passwords.size + 1}" }
                                    authManager.addPasswordWithLabel(tempPassword, label)
                                }
                                
                                Toast.makeText(context, context.getString(R.string.security_saved_success), Toast.LENGTH_SHORT).show()
                                
                                passwords = authManager.getStoredPasswordLabels()
                                tempPassword = ""
                                tempPasswordLabel = ""
                                currentPasswordForEdit = ""
                                actionError = ""
                                showAddDialog = false
                                showEditDialog = null
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(stringResource(R.string.save), color = DarkBg, fontWeight = FontWeight.Bold) }
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showAddDialog = false
                    showEditDialog = null
                    actionError = ""
                    currentPasswordForEdit = ""
                }) {
                    Text(stringResource(R.string.cancel), color = TextHint)
                }
            }
        )
    }

    // Delete Auth Dialog
    if (showDeleteAuthDialog != null) {
        var deleteAuthInput by remember { mutableStateOf("") }
        var deleteAuthError by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showDeleteAuthDialog = null },
            containerColor = DarkSurface,
            title = { Text(stringResource(R.string.password_mgmt_confirm_delete), color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(stringResource(R.string.password_mgmt_confirm_delete_desc), color = TextSecondary, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = deleteAuthInput,
                        onValueChange = { deleteAuthInput = it },
                        label = { Text(stringResource(R.string.password_mgmt_current_password), color = TextHint) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = AccentCyan
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (deleteAuthError.isNotEmpty()) {
                        Text(deleteAuthError, color = DangerRed, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            },
            confirmButton = {
                val context = LocalContext.current
                val activity = context as? androidx.fragment.app.FragmentActivity
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (activity != null) {
                        IconButton(onClick = {
                            authManager.showBiometricPrompt(
                                activity = activity,
                                onSuccess = {
                                    scope.launch {
                                        val currentEntries = com.focusguard.database.AppDatabase.getDatabase(context).appPasswordDao().getAllStatic()
                                        if (showDeleteAuthDialog!! in currentEntries.indices) {
                                            com.focusguard.database.AppDatabase.getDatabase(context).appPasswordDao().delete(currentEntries[showDeleteAuthDialog!!])
                                            passwords = authManager.getStoredPasswordLabels()
                                            showDeleteAuthDialog = null
                                        }
                                    }
                                },
                                onError = { deleteAuthError = it }
                            )
                        }) {
                            Icon(Icons.Default.Fingerprint, null, tint = AccentCyan)
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    
                    Button(
                        onClick = {
                            scope.launch {
                                if (authManager.verifyAndRemovePasswordByIndex(showDeleteAuthDialog!!, deleteAuthInput)) {
                                    passwords = authManager.getStoredPasswordLabels()
                                    showDeleteAuthDialog = null
                                } else {
                                    deleteAuthError = "Senha incorreta."
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(stringResource(R.string.remove_button), color = TextPrimary, fontWeight = FontWeight.Bold) }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAuthDialog = null }) {
                    Text(stringResource(R.string.cancel), color = TextHint)
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.manage_passwords), color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        },
        floatingActionButton = {
            if (isAuthenticated) {
                FloatingActionButton(
                    onClick = { 
                        tempPassword = ""
                        tempPasswordLabel = ""
                        showAddDialog = true 
                    },
                    containerColor = AccentCyan,
                    contentColor = DarkBg,
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_app_button)) // Using existing add_app for add icon desc
                }
            }
        },
        containerColor = DarkBg
    ) { paddingValues ->

        if (!isAuthenticated) {
            // [L2] Authentication gate with improved layout and security [S3]
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(32.dp)
                    .offset(x = animatedShakeOffset.dp), // [L1] Shake animation
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                val infiniteTransition = rememberInfiniteTransition()
                val pulseScale by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1500, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    )
                )

                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .graphicsLayer {
                            scaleX = pulseScale
                            scaleY = pulseScale
                        }
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(AccentCyan.copy(alpha = 0.15f), AccentPurple.copy(alpha = 0.15f))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isAuthenticated) Icons.Default.LockOpen else Icons.Default.Lock,
                        contentDescription = null,
                        tint = AccentCyan,
                        modifier = Modifier.size(40.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    "Área Protegida",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = TextPrimary
                )
                Text(
                    "Confirme sua identidade para gerenciar suas chaves de segurança.",
                    fontSize = 14.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp)
                )

                Spacer(modifier = Modifier.height(40.dp))

                OutlinedTextField(
                    value = authInput,
                    onValueChange = { authInput = it },
                    label = { Text(stringResource(R.string.security_master_password), color = TextHint) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = if (authError.isNotEmpty()) DangerRed else AccentCyan,
                        unfocusedBorderColor = if (authError.isNotEmpty()) DangerRed else Border
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (authError.isNotEmpty()) {
                    Text(
                        authError,
                        color = DangerRed,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                if (activity != null) {
                    Button(
                        onClick = {
                            authManager.showBiometricPrompt(
                                activity = activity,
                                onSuccess = { isAuthenticated = true; authError = "" },
                                onError = { authError = it }
                            )
                        },
                        modifier = Modifier.fillMaxWidth().height(54.dp).padding(bottom = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkSurface),
                        border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.3f)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.Fingerprint, null, tint = AccentCyan)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.security_biometric_auth), color = TextPrimary, fontWeight = FontWeight.Bold)
                    }
                }

                Button(
                    onClick = {
                        scope.launch {
                            if (authManager.verifyPassword(authInput)) {
                                isAuthenticated = true
                                authError = ""
                            } else {
                                // [S3] Security Hardening: Count failed attempts
                                val failed = authManager.incrementFailedAttempts()
                                val limit = authManager.getMaxPasswordAttempts()
                                
                                shakeOffset = 20f // Trigger shake

                                authError = when {
                                    limit > 0 && failed >= limit -> {
                                        if (authManager.isPhotoCaptureEnabled()) {
                                            activity?.let { cameraManager?.setupAndCaptureSilent(it) { _ -> } }
                                        }
                                        "Limite excedido! Foto capturada."
                                    }
                                    limit > 0 -> "Senha incorreta ($failed/$limit)"
                                    else -> "Senha incorreta"
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(stringResource(R.string.security_unlock_access), fontWeight = FontWeight.ExtraBold, color = DarkBg)
                }
            }
        } else {
            // Password list
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text(
                        "Tipo de Bloqueio Principal",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("NUMERIC" to "Senha", "PATTERN" to "Padrão", "DIGITAL" to "Digital").forEach { (type, label) ->
                            FilterChip(
                                selected = currentAuthType == type,
                                onClick = { 
                                    currentAuthType = type
                                    authManager.setPreferredAuthType(type)
                                },
                                label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AccentCyan,
                                    selectedLabelColor = DarkBg,
                                    containerColor = DarkSurface,
                                    labelColor = TextSecondary
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                if (passwords.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.Key, contentDescription = null, tint = TextHint, modifier = Modifier.size(64.dp))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(stringResource(R.string.security_none_saved), color = TextHint, fontSize = 16.sp)
                        }
                    }
                } else {
                    item {
                        Text(
                            "${passwords.size} senha(s) cadastrada(s)",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                        )
                    }

                    itemsIndexed(passwords) { index, label ->
                        PasswordCard(
                            index = index + 1,
                            label = label,
                            onEdit = {
                                tempPassword = "" // For security, don't show old password hash
                                tempPasswordLabel = label
                                showEditDialog = index
                            },
                            onDelete = {
                                showDeleteAuthDialog = index
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PasswordCard(index: Int, label: String, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = BorderStroke(1.dp, CardBorder), // [L2] Added border
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(AccentCyan.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Text("$index", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                Text("••••••••", fontSize = 13.sp, color = TextHint)
            }

            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.editar), tint = AccentCyan.copy(alpha = 0.7f))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.sessions_remove_item), tint = DangerRed.copy(alpha = 0.7f))
            }
        }
    }
}