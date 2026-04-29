package com.focusguard.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import com.focusguard.database.AppDatabase
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.ui.compose.screens.AppSelectionScreen
import com.focusguard.ui.compose.screens.SelectableAppUi
import com.focusguard.ui.compose.theme.*
import java.util.Calendar

class CreateSessionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val sessionType = intent.getStringExtra("session_type") ?: "APP"
        
        setContent {
            FocusGuardTheme {
                CreateSessionFlow(sessionType) {
                    finish()
                }
            }
        }
    }
}

@Composable
fun CreateSessionFlow(sessionType: String, onFinish: () -> Unit) {
    var currentStep by remember { mutableStateOf(1) }
    var selectedApps by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedSites by remember { mutableStateOf<List<String>>(emptyList()) }

    Scaffold(
        containerColor = DarkBg
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (currentStep) {
                1 -> {
                    if (sessionType == "APP") {
                        AppSelectionScreen(
                            onSelectionConfirmed = { apps ->
                                selectedApps = apps
                                currentStep = 2
                            },
                            onBack = onFinish
                        )
                    } else {
                        // Website selection logic would go here
                        currentStep = 2
                    }
                }
                2 -> {
                    ConfigSessionStep(
                        sessionType = sessionType,
                        apps = selectedApps,
                        sites = selectedSites,
                        onFinish = onFinish,
                        onBack = { currentStep = 1 }
                    )
                }
            }
        }
    }
}

fun android.content.Context.findActivity(): ComponentActivity? {
    var context = this
    while (context is android.content.ContextWrapper) {
        if (context is ComponentActivity) return context
        context = context.baseContext
    }
    return null
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConfigSessionStep(sessionType: String, apps: List<String>, sites: List<String>, onFinish: () -> Unit, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activityContext = context.findActivity() ?: context
    val sessionManager = remember { BlockingSessionManager.getInstance(context) }
    
    var isFixed24h by remember { mutableStateOf(true) }
    var useSpecificTime by remember { mutableStateOf(false) }
    
    var startHour by remember { mutableStateOf("08") }
    var startMin by remember { mutableStateOf("00") }
    var endHour by remember { mutableStateOf("20") }
    var endMin by remember { mutableStateOf("00") }
    
    // Days selection: 1=Dom, 2=Seg, 3=Ter, 4=Qua, 5=Qui, 6=Sex, 7=Sáb
    var selectedDays by remember { mutableStateOf(setOf("2", "3", "4", "5", "6")) } 
    val dayLabels = listOf("Dom" to "1", "Seg" to "2", "Ter" to "3", "Qua" to "4", "Qui" to "5", "Sex" to "6", "Sáb" to "7")

    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = TextPrimary)
            }
            Text("Configurar Bloqueio", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text("Tipo de Bloqueio:", color = TextPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(12.dp))
        
        Row(modifier = Modifier.fillMaxWidth()) {
            FilterChip(
                selected = isFixed24h,
                onClick = { isFixed24h = true },
                label = { Text("Padrão (24h)") },
                modifier = Modifier.weight(1f).padding(end = 4.dp),
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AccentCyan, selectedLabelColor = DarkBg)
            )
            FilterChip(
                selected = !isFixed24h,
                onClick = { isFixed24h = false },
                label = { Text("Dias Específicos") },
                modifier = Modifier.weight(1f).padding(start = 4.dp),
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AccentCyan, selectedLabelColor = DarkBg)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (!isFixed24h) {
            Text("Selecione os dias da semana:", color = TextPrimary, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(12.dp))
            
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 4
            ) {
                dayLabels.forEach { (label, value) ->
                    FilterChip(
                        selected = selectedDays.contains(value),
                        onClick = {
                            selectedDays = if (selectedDays.contains(value)) selectedDays - value else selectedDays + value
                        },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = AccentCyan, selectedLabelColor = DarkBg)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        HorizontalDivider(color = Border, thickness = 1.dp)
        Spacer(modifier = Modifier.height(32.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = useSpecificTime,
                onCheckedChange = { useSpecificTime = it },
                colors = CheckboxDefaults.colors(checkedColor = AccentCyan)
            )
            Text("Especificar horário de início e término?", color = TextPrimary)
        }

        if (useSpecificTime) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TimePickerField(label = "Início", hour = startHour, minute = startMin) { h, m ->
                    startHour = h; startMin = m
                }
                Icon(Icons.Default.Add, contentDescription = null, tint = TextHint, modifier = Modifier.padding(horizontal = 8.dp))
                TimePickerField(label = "Término", hour = endHour, minute = endMin) { h, m ->
                    endHour = h; endMin = m
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                val finalDays = if (isFixed24h) "1,2,3,4,5,6,7" else selectedDays.joinToString(",")
                val finalStartTime = if (useSpecificTime) "$startHour:$startMin" else "00:00"
                val finalEndTime = if (useSpecificTime) "$endHour:$endMin" else "23:59"

                scope.launch {
                    sessionManager.createSession(
                        name = "Sessão Customizada",
                        apps = apps,
                        websites = sites,
                        daysOfWeek = finalDays,
                        startTime = finalStartTime,
                        endTime = finalEndTime
                    )
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Bloqueio configurado com sucesso!", Toast.LENGTH_SHORT).show()
                        onFinish()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("ATIVAR BLOQUEIO", color = DarkBg, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
    }
}

@Composable
fun TimePickerField(label: String, hour: String, minute: String, onTimeSelected: (String, String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    
    OutlinedCard(
        onClick = {
            val picker = android.app.TimePickerDialog(context, { _, h, m ->
                onTimeSelected(h.toString().padStart(2, '0'), m.toString().padStart(2, '0'))
            }, hour.toInt(), minute.toInt(), true)
            picker.show()
        },
        modifier = Modifier.width(140.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = DarkSurface),
        border = BorderStroke(1.dp, Border)
    ) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 12.sp, color = TextSecondary)
            Text("$hour:$minute", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        }
    }
}
