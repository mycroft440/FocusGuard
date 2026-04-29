package com.focusguard.ui.compose.screens

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.database.BlockSession
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.ui.CreateSessionActivity
import com.focusguard.ui.compose.theme.*
import com.focusguard.security.AuthManager
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsListScreen(
    sessionType: String, // "PASSWORD" or "TIME"
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val authManager = remember { AuthManager(context) }
    val sessionManager = remember { BlockingSessionManager.getInstance(context) }
    val sessions by sessionManager.activeSessionsFlow.collectAsState(initial = emptyList())
    val filteredSessions = sessions.filter { it.sessionType == sessionType }
    
    var showPasswordPrompt by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    
    val title = if (sessionType == "PASSWORD") "Bloqueios por Senha" else "Bloqueios por Tempo"
    val icon = if (sessionType == "PASSWORD") Icons.Default.VpnKey else Icons.Default.Timer

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, color = TextPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
            )
        },
        containerColor = DarkBg,
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    val intent = Intent(context, CreateSessionActivity::class.java).apply {
                        putExtra("SESSION_TYPE", sessionType)
                    }
                    context.startActivity(intent)
                },
                containerColor = AccentCyan,
                contentColor = DarkBg,
                shape = CircleShape
            ) {
                Icon(Icons.Default.Add, contentDescription = "Adicionar Novo")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            if (filteredSessions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = TextHint.copy(alpha = 0.3f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Nenhum bloqueio configurado",
                            color = TextHint,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Toque no + para criar seu primeiro bloqueio.",
                            color = TextHint.copy(alpha = 0.6f),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                Text(
                    "Seus Bloqueios Ativos",
                    color = TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
                
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredSessions, key = { it.id }) { session ->
                        SessionListItem(
                            session = session, 
                            sessionManager = sessionManager,
                            onDeleteClick = { showPasswordPrompt = session.id }
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) } // Space for FAB
                }
            }
        }
    }

    if (showPasswordPrompt != null) {
        PasswordPromptDialog(
            onDismiss = { showPasswordPrompt = null },
            onConfirm = { password ->
                scope.launch {
                    if (authManager.verifyPassword(password)) {
                        sessionManager.endSession(showPasswordPrompt!!)
                        showPasswordPrompt = null
                    } else {
                        Toast.makeText(context, "Senha incorreta", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }
}

@Composable
fun PasswordPromptDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Encerrar Bloqueio", color = TextPrimary) },
        text = {
            Column {
                Text("Digite sua senha de segurança para encerrar este bloqueio.", color = TextSecondary, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Senha") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, focusedBorderColor = AccentCyan)
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(password) }, colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)) {
                Text("Confirmar", color = DarkBg)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = TextSecondary)
            }
        },
        containerColor = DarkSurface,
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
fun SessionListItem(
    session: BlockSession, 
    sessionManager: BlockingSessionManager,
    onDeleteClick: () -> Unit
) {
    val context = LocalContext.current
    val dateFormatter = remember { java.text.SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
    val isCurrentlyActive = sessionManager.isCurrentlyInBlockingWindow(session)
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = BorderStroke(
            width = if (isCurrentlyActive) 1.5.dp else 1.dp,
            color = if (isCurrentlyActive) AccentCyan.copy(alpha = 0.5f) else CardBorder
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (isCurrentlyActive) AccentCyan.copy(alpha = 0.1f) else DarkCardElevated),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (session.sessionType == "PASSWORD") Icons.Default.Lock else Icons.Default.HourglassBottom,
                        contentDescription = null,
                        tint = if (isCurrentlyActive) AccentCyan else TextHint,
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                Spacer(Modifier.width(12.dp))
                
                Column(Modifier.weight(1f)) {
                    Text(
                        if (session.sessionType == "PASSWORD") "Bloqueio por Senha" else "Bloqueio por Tempo",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        if (session.isFixed24h) "Modo: Fixo 24h" else "Modo: Agendado",
                        color = TextHint,
                        fontSize = 12.sp
                    )
                }
                
                if (isCurrentlyActive) {
                    Surface(
                        color = AccentCyan.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "ATIVO AGORA",
                            color = AccentCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                if (session.sessionType == "PASSWORD") {
                    IconButton(onClick = onDeleteClick) {
                        Icon(Icons.Default.Delete, contentDescription = "Excluir", tint = DangerRed.copy(alpha = 0.7f))
                    }
                }
            }
            
            Spacer(Modifier.height(16.dp))
            
            // Stats Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoTag(Icons.Default.Apps, "${session.blockedAppsCount} Apps")
                InfoTag(Icons.Default.Language, "${session.blockedWebsitesCount} Sites")
                
                if (session.sessionType == "TIME" && session.endTime != null) {
                    val remainingMs = session.endTime - System.currentTimeMillis()
                    val remainingDays = TimeUnit.MILLISECONDS.toDays(remainingMs)
                    val remainingHours = TimeUnit.MILLISECONDS.toHours(remainingMs) % 24
                    InfoTag(Icons.Default.AccessTime, "${remainingDays}d ${remainingHours}h")
                }
            }
            
            if (!session.isFixed24h) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = CardBorder, thickness = 0.5.dp)
                Spacer(Modifier.height(12.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.EventRepeat, null, tint = TextHint, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Horário: ${String.format("%02d:%02d", session.recurringStartHour, session.recurringStartMinute)} - ${String.format("%02d:%02d", session.recurringEndHour, session.recurringEndMinute)}",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
fun InfoTag(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = TextHint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = TextSecondary, fontSize = 12.sp)
    }
}
