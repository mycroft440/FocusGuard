package com.focusguard

import android.content.Context
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.focusguard.ui.compose.theme.*

import androidx.compose.runtime.*
import android.content.Intent
import com.focusguard.security.AuthManager
import com.focusguard.manager.BlockingSessionManager
import kotlinx.coroutines.launch

class BlockScreenActivity : FragmentActivity() {
    private var blockedNameState = mutableStateOf("Este aplicativo")
    private var isPasswordSessionState = mutableStateOf(false)
    private lateinit var authManager: AuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authManager = AuthManager(this)
        
        blockedNameState.value = intent.getStringExtra("BLOCKED_NAME") ?: "Este aplicativo"
        isPasswordSessionState.value = intent.getBooleanExtra("IS_PASSWORD_SESSION", false)

        setContent {
            FocusGuardTheme {
                val currentBlockedName by blockedNameState
                val isPasswordSession by isPasswordSessionState
                BlockScreenContent(
                    blockedName = currentBlockedName,
                    isPasswordSession = isPasswordSession,
                    onClose = { finish() },
                    authManager = authManager,
                    context = this
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newName = intent?.getStringExtra("BLOCKED_NAME") ?: "Este aplicativo"
        val isPassword = intent?.getBooleanExtra("IS_PASSWORD_SESSION", false) ?: false
        blockedNameState.value = newName
        isPasswordSessionState.value = isPassword
        android.util.Log.d("BlockScreen", "onNewIntent recebido: Atualizando para $newName (Senha: $isPassword)")
    }
}

@Composable
fun BlockScreenContent(
    blockedName: String, 
    isPasswordSession: Boolean,
    onClose: () -> Unit, 
    authManager: AuthManager,
    context: Context
) {
    val prefs = remember { context.getSharedPreferences("FocusGuardBlockCustom", Context.MODE_PRIVATE) }
    val customText = prefs.getString("block_text", "Você é mais forte que sua distração!") ?: "Você é mais forte que sua distração!"
    val imageUriString = prefs.getString("block_image_uri", "") ?: ""
    val scope = rememberCoroutineScope()
    var showPasswordDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
    ) {
        if (imageUriString.isNotEmpty()) {
            Image(
                painter = rememberAsyncImagePainter(imageUriString),
                contentDescription = "Background",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                alpha = 0.5f // Dim the background image a bit
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = "Bloqueado",
                tint = DangerRed,
                modifier = Modifier.size(64.dp)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = "Acesso Bloqueado",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "$blockedName está bloqueado por uma sessão ativa do FocusGuard.",
                fontSize = 16.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(48.dp))
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkCard.copy(alpha = 0.8f)),
                border = BorderStroke(1.dp, CardBorder)
            ) {
                Text(
                    text = "\"$customText\"",
                    fontSize = 18.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    color = AccentCyan,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp).fillMaxWidth()
                )
            }
            
            Spacer(modifier = Modifier.height(48.dp))
            
            if (isPasswordSession) {
                Button(
                    onClick = {
                        authManager.showBiometricPrompt(
                            activity = context as androidx.fragment.app.FragmentActivity,
                            onSuccess = {
                                BlockingSessionManager.getInstance(context).endPasswordSessions()
                                onClose()
                            },
                            onError = { _ ->
                                showPasswordDialog = true
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Fingerprint, contentDescription = null, tint = DarkBg)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Desbloquear com Digital", color = DarkBg, fontWeight = FontWeight.Bold)
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                TextButton(onClick = { showPasswordDialog = true }) {
                    Text("Usar Senha Alternativa", color = AccentCyan)
                }
                
                Spacer(modifier = Modifier.height(24.dp))
            }
            
            Button(
                onClick = onClose,
                modifier = Modifier.height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DarkCard),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, CardBorder)
            ) {
                Icon(Icons.Default.Close, contentDescription = null, tint = TextPrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Entendi, vou focar", color = TextPrimary)
            }
        }
    }

    if (showPasswordDialog) {
        var password by remember { mutableStateOf("") }
        var isError by remember { mutableStateOf(false) }
        
        AlertDialog(
            onDismissRequest = { showPasswordDialog = false },
            title = { Text("Confirmar Senha", color = TextPrimary) },
            text = {
                Column {
                    Text("Digite sua senha para encerrar o bloqueio por senha.", color = TextSecondary)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; isError = false },
                        label = { Text("Senha") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        isError = isError,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            unfocusedTextColor = TextPrimary,
                            focusedTextColor = TextPrimary
                        )
                    )
                    if (isError) {
                        Text("Senha incorreta", color = DangerRed, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        if (authManager.verifyPassword(password)) {
                            BlockingSessionManager.getInstance(context).endPasswordSessions()
                            showPasswordDialog = false
                            onClose()
                        } else {
                            isError = true
                        }
                    }
                }) {
                    Text("Desbloquear", color = AccentCyan)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasswordDialog = false }) {
                    Text("Cancelar", color = TextHint)
                }
            },
            containerColor = DarkSurface
        )
    }
}
