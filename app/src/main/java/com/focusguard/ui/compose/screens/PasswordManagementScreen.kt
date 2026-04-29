package com.focusguard.ui.compose.screens

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.security.AuthManager
import com.focusguard.ui.compose.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordManagementScreen(authManager: AuthManager, onBack: () -> Unit) {
    val hasPasswords = authManager.hasPasswordSet()
    var isAuthenticated by remember { mutableStateOf(!hasPasswords) }
    var authInput by remember { mutableStateOf("") }
    var authError by remember { mutableStateOf("") }

    // Password list state
    var passwords by remember { mutableStateOf(authManager.getStoredPasswordLabels()) }
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
            title = { Text(if (isEditing) "Editar Senha" else "Nova Senha", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    if (isEditing) {
                        Text("Digite sua senha atual para confirmar a alteração.", color = TextSecondary, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = currentPasswordForEdit,
                            onValueChange = { currentPasswordForEdit = it },
                            label = { Text("Senha Atual", color = TextHint) },
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
                        label = { Text("Nome/Rótulo", color = TextHint) },
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
                        label = { Text("Senha (números)", color = TextHint) },
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
                Button(
                    onClick = {
                        if (tempPassword.isBlank()) {
                            actionError = "A senha não pode ser vazia."
                            return@Button
                        }
                        
                        if (isEditing) {
                            if (authManager.verifyAndUpdatePasswordByIndex(showEditDialog!!, currentPasswordForEdit, tempPassword, tempPasswordLabel)) {
                                // Success
                            } else {
                                actionError = "Senha atual incorreta."
                                return@Button
                            }
                        } else {
                            val label = tempPasswordLabel.ifBlank { "Senha ${passwords.size + 1}" }
                            authManager.addPasswordWithLabel(tempPassword, label)
                        }
                        
                        Toast.makeText(context, "Bloqueio configurado com sucesso!", Toast.LENGTH_SHORT).show()
                        
                        passwords = authManager.getStoredPasswordLabels()
                        tempPassword = ""
                        tempPasswordLabel = ""
                        currentPasswordForEdit = ""
                        actionError = ""
                        showAddDialog = false
                        showEditDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Salvar", color = DarkBg, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showAddDialog = false
                    showEditDialog = null
                    actionError = ""
                    currentPasswordForEdit = ""
                }) {
                    Text("Cancelar", color = TextHint)
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
            title = { Text("Confirmar Exclusão", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Digite sua senha atual para confirmar a exclusão desta senha.", color = TextSecondary, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = deleteAuthInput,
                        onValueChange = { deleteAuthInput = it },
                        label = { Text("Senha Atual", color = TextHint) },
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
                Button(
                    onClick = {
                        if (authManager.verifyAndRemovePasswordByIndex(showDeleteAuthDialog!!, deleteAuthInput)) {
                            passwords = authManager.getStoredPasswordLabels()
                            showDeleteAuthDialog = null
                        } else {
                            deleteAuthError = "Senha incorreta."
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Excluir", color = TextPrimary, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAuthDialog = null }) {
                    Text("Cancelar", color = TextHint)
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gerenciar Senhas", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
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
                    Icon(Icons.Default.Add, contentDescription = "Adicionar Senha")
                }
            }
        },
        containerColor = DarkBg
    ) { paddingValues ->

        if (!isAuthenticated) {
            // Authentication gate
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(AccentCyan.copy(alpha = 0.15f), AccentPurple.copy(alpha = 0.15f))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(40.dp))
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    "Área Protegida",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    "Digite uma das suas senhas para visualizar e gerenciar.",
                    fontSize = 14.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp)
                )

                Spacer(modifier = Modifier.height(32.dp))

                OutlinedTextField(
                    value = authInput,
                    onValueChange = { authInput = it },
                    label = { Text("Senha", color = TextHint) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = AccentCyan
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (authError.isNotEmpty()) {
                    Text(authError, color = DangerRed, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        if (authManager.verifyPassword(authInput)) {
                            isAuthenticated = true
                            authError = ""
                        } else {
                            authError = "Senha incorreta."
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Desbloquear", fontWeight = FontWeight.Bold, color = DarkBg)
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
                            Text("Nenhuma senha cadastrada.", color = TextHint, fontSize = 16.sp)
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
                Icon(Icons.Default.Edit, contentDescription = "Editar", tint = AccentCyan.copy(alpha = 0.7f))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Remover", tint = DangerRed.copy(alpha = 0.7f))
            }
        }
    }
}
