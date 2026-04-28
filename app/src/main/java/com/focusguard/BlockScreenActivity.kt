package com.focusguard

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
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

class BlockScreenActivity : ComponentActivity() {
    private var blockedNameState = mutableStateOf("Este aplicativo")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        blockedNameState.value = intent.getStringExtra("BLOCKED_NAME") ?: "Este aplicativo"

        setContent {
            FocusGuardTheme {
                val currentBlockedName by blockedNameState
                BlockScreenContent(
                    blockedName = currentBlockedName,
                    onClose = { finish() },
                    context = this
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        blockedNameState.value = intent?.getStringExtra("BLOCKED_NAME") ?: "Este aplicativo"
    }
}

@Composable
fun BlockScreenContent(blockedName: String, onClose: () -> Unit, context: Context) {
    val prefs = remember { context.getSharedPreferences("FocusGuardBlockCustom", Context.MODE_PRIVATE) }
    val customText = prefs.getString("block_text", "Você é mais forte que sua distração!") ?: "Você é mais forte que sua distração!"
    val imageUriString = prefs.getString("block_image_uri", "") ?: ""

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
}
