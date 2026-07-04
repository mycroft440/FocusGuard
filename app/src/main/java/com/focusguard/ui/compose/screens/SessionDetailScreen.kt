package com.focusguard.ui.compose.screens

import kotlin.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.text.style.TextAlign
import com.focusguard.R
import com.focusguard.database.AppDatabase
import com.focusguard.database.BlockSession
import com.focusguard.ui.compose.rememberAppDatabase
import com.focusguard.ui.compose.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    sessionType: String,
    onBack: () -> Unit,
    onAddNewBlock: () -> Unit
) {
    val context = LocalContext.current
    // P2-1: usa Hilt EntryPoint via rememberAppDatabase() em vez de
    // AppDatabase.getDatabase(context) direto (delega para o mesmo singleton
    // mas arquiteturalmente correto — UI acessa DB via DI, não bypass).
    val db = rememberAppDatabase()
    var sessions by remember { mutableStateOf<List<BlockSession>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    val title = when (sessionType) {
        "PASSWORD" -> stringResource(R.string.session_detail_password_block)
        "TIME" -> stringResource(R.string.session_detail_time_block)
        else -> stringResource(R.string.limits_title)
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val activeSessions = db.blockSessionDao().getAllActiveSessionsStatic()
                .filter { it.sessionType == sessionType }
            sessions = activeSessions
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, color = TextPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = TextPrimary)
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
                .padding(16.dp)
        ) {
            if (isLoading) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentCyan)
                }
            } else if (sessions.isEmpty()) {
                EmptySessionCard(sessionType)
            } else {
                sessions.forEach { session ->
                    SessionDetailCard(session)
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Add New Block Button
            Button(
                onClick = onAddNewBlock,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = DarkBg)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.session_detail_add_new), color = DarkBg, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun EmptySessionCard(type: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(AccentCyan.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (type == "PASSWORD") Icons.Default.Lock else Icons.Default.Timer,
                    contentDescription = null,
                    tint = AccentCyan,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                stringResource(R.string.session_detail_none_active),
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.session_detail_none_active_desc),
                color = TextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun SessionDetailCard(session: BlockSession) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // P2-1: usa Hilt EntryPoint via rememberAppDatabase()
    val db = rememberAppDatabase()
    var apps by remember { mutableStateOf<List<String>>(emptyList()) }
    var sites by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(expanded) {
        if (expanded && apps.isEmpty() && sites.isEmpty()) {
            withContext(Dispatchers.IO) {
                apps = db.sessionAppCrossRefDao().getAppsForSessions(listOf(session.id))
                sites = db.sessionWebsiteCrossRefDao().getWebsitesForSessions(listOf(session.id))
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AccentCyan.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (session.sessionType == "PASSWORD") Icons.Default.Lock else Icons.Default.Timer,
                        null,
                        tint = AccentCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (session.sessionType == "PASSWORD") stringResource(R.string.session_detail_password_session) else stringResource(R.string.session_detail_time_session),
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        if (session.isFixed24h) stringResource(R.string.session_detail_fixed_24h) else stringResource(R.string.session_detail_scheduled),
                        fontSize = 12.sp,
                        color = TextHint
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null,
                        tint = TextHint
                    )
                }
            }

            if (session.sessionType == "TIME" && session.endTime != null) {
                Spacer(modifier = Modifier.height(12.dp))
                val diff = session.endTime - System.currentTimeMillis()
                if (diff > 0) {
                    val days = diff / (24 * 3600 * 1000)
                    val hours = (diff / (3600 * 1000)) % 24
                    val mins = (diff / (60 * 1000)) % 60
                    Text(
                        stringResource(R.string.session_detail_expires_in, "${days}d ${hours}h ${mins}m"),
                        color = AccentCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(modifier = Modifier.height(16.dp))
                    Divider(color = CardBorder)
                    Spacer(modifier = Modifier.height(16.dp))

                    if (apps.isNotEmpty()) {
                        Text(stringResource(R.string.session_detail_apps_blocked), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AccentPurple)
                        Spacer(modifier = Modifier.height(8.dp))
                        apps.forEach { pkg ->
                            AppItemRow(pkg)
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    if (sites.isNotEmpty()) {
                        Text(stringResource(R.string.session_detail_sites_blocked), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
                        Spacer(modifier = Modifier.height(8.dp))
                        sites.forEach { site ->
                            SiteItemRow(site)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppItemRow(packageName: String) {
    val context = LocalContext.current
    var appName by remember { mutableStateOf(packageName) }
    var iconBmp by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    LaunchedEffect(packageName) {
        withContext(Dispatchers.IO) {
            try {
                val pm = context.packageManager
                val info = pm.getApplicationInfo(packageName, 0)
                appName = pm.getApplicationLabel(info).toString()
                val drawable = pm.getApplicationIcon(info)
                iconBmp = drawable.toBitmap(60, 60).asImageBitmap()
            } catch (_: Exception) {}
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        if (iconBmp != null) {
            Image(iconBmp!!, null, Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)))
        } else {
            Icon(Icons.Default.Android, null, Modifier.size(24.dp), tint = TextHint)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(appName, fontSize = 14.sp, color = TextSecondary)
    }
}

@Composable
fun SiteItemRow(domain: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Icon(Icons.Default.Public, null, Modifier.size(24.dp), tint = AccentCyan.copy(alpha = 0.6f))
        Spacer(modifier = Modifier.width(12.dp))
        Text(domain, fontSize = 14.sp, color = TextSecondary)
    }
}