package com.focusguard.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.AuthManager
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.DarkCard
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FinalConfigStep(
    sessionType: String,
    authManager: AuthManager,
    sites: List<String>,
    apps: List<String>,
    onFinish: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var hasPassword by remember { mutableStateOf(false) }
    var showPasswordCreationDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        hasPassword = authManager.hasPasswordSet()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configurar bloqueio por senha", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
            )
        },
        containerColor = DarkBg
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Bloqueio por senha", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (hasPassword) "Senha existente encontrada." else "Nenhuma senha encontrada. Crie uma senha para continuar.",
                        color = if (hasPassword) AccentCyan else DangerRed
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    if (!hasPassword) {
                        Button(
                            onClick = { showPasswordCreationDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Criar senha", color = DarkBg)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    if (!hasPassword) {
                        showPasswordCreationDialog = true
                    } else {
                        BlockingSessionManager.getInstance(context).startPasswordSession(
                            isFixed24h = true,
                            startHour = 0,
                            endHour = 24,
                            startMinute = 0,
                            endMinute = 0,
                            daysOfWeek = "",
                            apps = apps,
                            sites = sites
                        )
                        Toast.makeText(context, "Bloqueio ativado com sucesso", Toast.LENGTH_LONG).show()
                        onFinish()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Ativar bloqueio", color = DarkBg, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showPasswordCreationDialog) {
        PasswordCreationDialog(
            onDismiss = { showPasswordCreationDialog = false },
            onPasswordCreated = { password ->
                authManager.addPassword(password)
                hasPassword = true
                showPasswordCreationDialog = false
                Toast.makeText(context, "Senha criada com sucesso!", Toast.LENGTH_LONG).show()
            }
        )
    }
}
