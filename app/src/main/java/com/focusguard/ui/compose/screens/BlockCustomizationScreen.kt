package com.focusguard.ui.compose.screens

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.focusguard.R
import com.focusguard.ui.compose.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockCustomizationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("FocusGuardBlockCustom", Context.MODE_PRIVATE) }
    
    var customText by remember { mutableStateOf(prefs.getString("block_text", "Você é mais forte que sua distração!") ?: "") }
    var imageUriString by remember { mutableStateOf(prefs.getString("block_image_uri", "") ?: "") }
    
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                // Solicita permissão persistente de leitura para que a imagem continue acessível após o reboot
                context.contentResolver.takePersistableUriPermission(
                    it, 
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                com.focusguard.utils.FocusGuardLogger.logError("Customization", "Erro ao persistir permissão de URI", e)
            }
            imageUriString = it.toString()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Personalizar Bloqueio", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        prefs.edit()
                            .putString("block_text", customText)
                            .putString("block_image_uri", imageUriString)
                            .apply()
                        Toast.makeText(context, "Configurações salvas!", Toast.LENGTH_SHORT).show()
                        onBack()
                    }) {
                        Icon(Icons.Default.Save, contentDescription = "Salvar", tint = AccentCyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
            )
        },
        containerColor = DarkBg
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Personalize a mensagem e a imagem que aparecem quando um app é bloqueado.",
                fontSize = 14.sp,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            Text(
                text = "Frase Inspiradora",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            OutlinedTextField(
                value = customText,
                onValueChange = { customText = it },
                placeholder = { Text("Ex: Foque no que importa!", color = TextHint) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = AccentCyan
                ),
                shape = RoundedCornerShape(16.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Imagem de Fundo",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { imagePickerLauncher.launch(arrayOf("image/*")) },
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                border = BorderStroke(1.dp, CardBorder)
            ) {
                if (imageUriString.isNotEmpty()) {
                    Image(
                        painter = rememberAsyncImagePainter(imageUriString),
                        contentDescription = "Imagem de bloqueio",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Image, contentDescription = null, tint = TextHint, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Toque para escolher uma imagem", color = TextHint, fontSize = 12.sp)
                        }
                    }
                }
            }
            
            if (imageUriString.isNotEmpty()) {
                TextButton(
                    onClick = { imageUriString = "" },
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)
                ) {
                    Text("Remover Imagem", color = DangerRed)
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
            
            Button(
                onClick = {
                    prefs.edit()
                        .putString("block_text", customText)
                        .putString("block_image_uri", imageUriString)
                        .apply()
                    Toast.makeText(context, "Configurações salvas!", Toast.LENGTH_SHORT).show()
                    onBack()
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
            ) {
                Text("Salvar Configurações", color = DarkBg, fontWeight = FontWeight.Bold)
            }
        }
    }
}
