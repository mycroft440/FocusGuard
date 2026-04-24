package com.focusguard.ui.compose.screens

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
    var newPassword by remember { mutableStateOf("") }
    var newPasswordLabel by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf("") }

    // Add password dialog
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            containerColor = DarkSurface,
            title = { Text("Nova Senha", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = newPasswordLabel,
                        onValueChange = { newPasswordLabel = it },
                        label = { Text("Nome/Rótulo (ex: Senha Principal)", color = TextHint) },
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
                        value = newPassword,
                        onValueChange = { newPassword = it },
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
                    if (addError.isNotEmpty()) {
                        Text(addError, color = DangerRed, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPassword.isBlank()) {
                            addError = "A senha não pode ser vazia."
                            return@Button
                        }
                        val label = newPasswordLabel.ifBlank { "Senha ${passwords.size + 1}" }
                        authManager.addPasswordWithLabel(newPassword, label)
                        passwords = authManager.getStoredPasswordLabels()
                        newPassword = ""
                        newPasswordLabel = ""
                        addError = ""
                        showAddDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Salvar", color = DarkBg, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false; addError = "" }) {
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
                    onClick = { showAddDialog = true },
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
            if (passwords.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Key, contentDescription = null, tint = TextHint, modifier = Modifier.size(64.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Nenhuma senha cadastrada.", color = TextHint, fontSize = 16.sp)
                    Text("Toque no botão + para adicionar.", color = TextHint, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
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
                            onDelete = {
                                authManager.removePasswordByIndex(index)
                                passwords = authManager.getStoredPasswordLabels()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PasswordCard(index: Int, label: String, onDelete: () -> Unit) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

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

            if (!showDeleteConfirm) {
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "Remover", tint = DangerRed.copy(alpha = 0.7f))
                }
            } else {
                Row {
                    IconButton(onClick = { showDeleteConfirm = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancelar", tint = TextHint)
                    }
                    IconButton(onClick = {
                        onDelete()
                        showDeleteConfirm = false
                    }) {
                        Icon(Icons.Default.CheckCircle, contentDescription = "Confirmar", tint = DangerRed)
                    }
                }
            }
        }
    }
}
