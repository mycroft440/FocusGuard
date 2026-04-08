package com.focusguard.ui.compose.screens

import android.graphics.drawable.Drawable
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.focusguard.database.BlockedApp
import com.focusguard.database.BlockedWebsite
import com.focusguard.ui.compose.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeSessionScreen(
    appsCount: Int,
    sitesCount: Int,
    selectedApps: List<Pair<BlockedApp, Drawable?>>,
    selectedSites: List<BlockedWebsite>,
    onSelectApps: () -> Unit,
    onSelectSites: () -> Unit,
    onStartSession: (days: Int, hours: Int) -> Unit,
    onBack: () -> Unit
) {
    var days by remember { mutableStateOf("") }
    var hours by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sessão por Tempo", color = TextPrimary) },
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
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            // Duration card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                border = BorderStroke(1.dp, CardBorder)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "⏱  Defina a duração",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = days,
                            onValueChange = { days = it.take(4) },
                            label = { Text("Dias") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentCyan,
                                focusedLabelColor = AccentCyan,
                                cursorColor = AccentCyan,
                                unfocusedTextColor = TextPrimary,
                                focusedTextColor = TextPrimary
                            ),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        OutlinedTextField(
                            value = hours,
                            onValueChange = { hours = it.take(4) },
                            label = { Text("Horas") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentCyan,
                                focusedLabelColor = AccentCyan,
                                cursorColor = AccentCyan,
                                unfocusedTextColor = TextPrimary,
                                focusedTextColor = TextPrimary
                            ),
                            singleLine = true
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = Divider, thickness = 1.dp)
            Spacer(modifier = Modifier.height(16.dp))

            // Selection card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                border = BorderStroke(1.dp, CardBorder)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "🚫  Selecione o que bloquear",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    OutlinedButton(
                        onClick = onSelectApps,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.5.dp, AccentCyan),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan)
                    ) {
                        Text("Selecionar Aplicativos")
                    }

                    Text(
                        text = "$appsCount apps selecionados",
                        fontSize = 13.sp,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )

                    // App icons row
                    if (selectedApps.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(bottom = 16.dp),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            selectedApps.forEach { (app, icon) ->
                                if (icon != null) {
                                    val bitmap = remember(app.packageName) {
                                        icon.toBitmap(72, 72).asImageBitmap()
                                    }
                                    Image(
                                        bitmap = bitmap,
                                        contentDescription = app.appName,
                                        modifier = Modifier
                                            .size(36.dp)
                                            .padding(horizontal = 2.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = onSelectSites,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.5.dp, AccentCyan),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan)
                    ) {
                        Text("Selecionar Sites")
                    }

                    Text(
                        text = "$sitesCount sites selecionados",
                        fontSize = 13.sp,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    )

                    // Sites badges
                    if (selectedSites.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            selectedSites.forEach { site ->
                                Surface(
                                    modifier = Modifier.padding(horizontal = 2.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    color = DarkCardElevated,
                                    border = BorderStroke(1.dp, CardBorder)
                                ) {
                                    Text(
                                        text = site.domain,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AccentCyan,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Start button
            Button(
                onClick = {
                    val d = days.toIntOrNull() ?: 0
                    val h = hours.toIntOrNull() ?: 0
                    onStartSession(d, h)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 16.dp)
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentCyan
                )
            ) {
                Text(
                    "Iniciar Sessão de Foco",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }
}
