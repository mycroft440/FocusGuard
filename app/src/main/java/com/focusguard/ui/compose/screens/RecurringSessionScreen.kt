package com.focusguard.ui.compose.screens

import android.app.TimePickerDialog
import android.graphics.drawable.Drawable
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.focusguard.database.BlockedApp
import com.focusguard.database.BlockedWebsite
import com.focusguard.ui.compose.theme.*
import java.util.Calendar
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringSessionScreen(
    appsCount: Int,
    sitesCount: Int,
    selectedApps: List<Pair<BlockedApp, Drawable?>>,
    selectedSites: List<BlockedWebsite>,
    onSelectApps: () -> Unit,
    onSelectSites: () -> Unit,
    onStartSession: (startH: Int, startM: Int, endH: Int, endM: Int, days: String, months: Int) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    var startHour by remember { mutableIntStateOf(-1) }
    var startMinute by remember { mutableIntStateOf(-1) }
    var endHour by remember { mutableIntStateOf(-1) }
    var endMinute by remember { mutableIntStateOf(-1) }
    var months by remember { mutableStateOf("") }

    val dayLabels = listOf("D", "S", "T", "Q", "Q", "S", "S")
    val dayStates = remember { mutableStateListOf(false, false, false, false, false, false, false) }

    val startTimeText = if (startHour >= 0) {
        String.format(Locale.getDefault(), "Início: %02d:%02d", startHour, startMinute)
    } else "Início: --:--"

    val endTimeText = if (endHour >= 0) {
        String.format(Locale.getDefault(), "Fim: %02d:%02d", endHour, endMinute)
    } else "Fim: --:--"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sessão Recorrente", color = TextPrimary) },
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
            // Time picker card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                border = BorderStroke(1.dp, CardBorder)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("🕐  Defina o Horário", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.padding(bottom = 16.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                val cal = Calendar.getInstance()
                                TimePickerDialog(context, { _, h, m ->
                                    startHour = h
                                    startMinute = m
                                }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.5.dp, AccentCyan),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan)
                        ) { Text(startTimeText, fontSize = 13.sp) }

                        Spacer(modifier = Modifier.width(8.dp))

                        OutlinedButton(
                            onClick = {
                                val cal = Calendar.getInstance()
                                TimePickerDialog(context, { _, h, m ->
                                    endHour = h
                                    endMinute = m
                                }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.5.dp, AccentCyan),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan)
                        ) { Text(endTimeText, fontSize = 13.sp) }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Days card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                border = BorderStroke(1.dp, CardBorder)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("📅  Dias da Semana", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.padding(bottom = 16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        dayLabels.forEachIndexed { index, label ->
                            val isSelected = dayStates[index]
                            Surface(
                                modifier = Modifier
                                    .padding(horizontal = 3.dp)
                                    .size(40.dp)
                                    .clickable { dayStates[index] = !isSelected },
                                shape = CircleShape,
                                color = if (isSelected) AccentCyan else DarkCardElevated,
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) AccentCyan else CardBorder
                                )
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = label,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) DarkBg else TextPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Duration card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                border = BorderStroke(1.dp, CardBorder)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("📆  Duração do Agendamento", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.padding(bottom = 16.dp))

                    OutlinedTextField(
                        value = months,
                        onValueChange = { months = it.take(2) },
                        label = { Text("Meses (Ex: 3)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
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

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = Divider, thickness = 1.dp)
            Spacer(modifier = Modifier.height(16.dp))

            // Apps/Sites card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                border = BorderStroke(1.dp, CardBorder)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("🚫  Selecione o que bloquear", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.padding(bottom = 16.dp))

                    OutlinedButton(
                        onClick = onSelectApps,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.5.dp, AccentCyan),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan)
                    ) { Text("Selecionar Aplicativos") }

                    Text("$appsCount apps selecionados", fontSize = 13.sp, color = TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))

                    if (selectedApps.isNotEmpty()) {
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                            selectedApps.forEach { (app, icon) ->
                                if (icon != null) {
                                    val bitmap = remember(app.packageName) { icon.toBitmap(72, 72).asImageBitmap() }
                                    Image(bitmap = bitmap, contentDescription = app.appName, modifier = Modifier.size(36.dp).padding(horizontal = 2.dp).clip(RoundedCornerShape(8.dp)))
                                }
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = onSelectSites,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.5.dp, AccentCyan),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan)
                    ) { Text("Selecionar Sites") }

                    Text("$sitesCount sites selecionados", fontSize = 13.sp, color = TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))

                    if (selectedSites.isNotEmpty()) {
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            selectedSites.forEach { site ->
                                Surface(modifier = Modifier.padding(horizontal = 2.dp), shape = RoundedCornerShape(8.dp), color = DarkCardElevated, border = BorderStroke(1.dp, CardBorder)) {
                                    Text(text = site.domain, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AccentCyan, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
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
                    val selectedDays = dayStates.mapIndexedNotNull { i, checked ->
                        if (checked) (i + 1).toString() else null
                    }.joinToString(",")
                    val m = months.toIntOrNull() ?: 1
                    onStartSession(startHour, startMinute, endHour, endMinute, selectedDays, m)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 16.dp)
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
            ) {
                Text("Agendar Bloqueio Recorrente", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}
