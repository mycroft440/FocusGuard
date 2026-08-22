package com.focusguard.ui.compose.screens

import kotlin.OptIn
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.focusguard.R
import com.focusguard.database.BlockSession
import com.focusguard.security.TimedBlockProtectionController
import com.focusguard.security.TimedBlockRevocationManager
import com.focusguard.ui.compose.rememberAppDatabase
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.AccentPurple
import com.focusguard.ui.compose.theme.CardBorder
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.DarkCard
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.utils.WebsiteBlocker
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    sessionType: String,
    onBack: () -> Unit,
    onAddNewBlock: () -> Unit
) {
    val context = LocalContext.current
    val db = rememberAppDatabase()
    val scope = rememberCoroutineScope()
    val timedProtection = remember(context) {
        TimedBlockProtectionController.getInstance(context)
    }
    val timedRevocation = remember(context) { TimedBlockRevocationManager(context) }
    var sessions by remember { mutableStateOf<List<BlockSession>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var sessionToRevoke by remember { mutableStateOf<BlockSession?>(null) }
    var masterPassword by remember { mutableStateOf("") }
    var revokeError by remember { mutableStateOf<String?>(null) }
    var revoking by remember { mutableStateOf(false) }

    val title = when (sessionType) {
        "PASSWORD" -> stringResource(R.string.session_detail_password_block)
        "TIME" -> stringResource(R.string.session_detail_time_block)
        else -> stringResource(R.string.limits_title)
    }

    suspend fun reloadSessions() {
        withContext(Dispatchers.IO) {
            sessions = db.blockSessionDao().getAllActiveSessionsStatic()
                .filter { it.sessionType == sessionType }
            isLoading = false
        }
    }

    LaunchedEffect(sessionType) {
        reloadSessions()
    }

    sessionToRevoke?.let { session ->
        AlertDialog(
            onDismissRequest = {
                if (!revoking) {
                    sessionToRevoke = null
                    masterPassword = ""
                    revokeError = null
                }
            },
            title = { Text(stringResource(R.string.time_block_revoke_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.time_block_revoke_desc))
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = masterPassword,
                        onValueChange = {
                            masterPassword = it
                            revokeError = null
                        },
                        enabled = !revoking,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    revokeError?.let {
                        Text(
                            text = it,
                            color = DangerRed,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = masterPassword.isNotBlank() && !revoking,
                    onClick = {
                        if (revoking) return@Button
                        revoking = true
                        scope.launch {
                            val result = timedRevocation.revokeSessionWithMasterCredential(
                                sessionId = session.id,
                                password = masterPassword
                            )
                            when (result) {
                                TimedBlockRevocationManager.Result.REVOKED -> {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.time_block_revoke_success),
                                        Toast.LENGTH_LONG
                                    ).show()
                                    sessionToRevoke = null
                                    masterPassword = ""
                                    revokeError = null
                                    reloadSessions()
                                }
                                TimedBlockRevocationManager.Result.WRONG_PASSWORD -> {
                                    revokeError = context.getString(
                                        R.string.time_block_revoke_wrong_password
                                    )
                                }
                                TimedBlockRevocationManager.Result.NOT_FOUND,
                                TimedBlockRevocationManager.Result.FAILED -> {
                                    revokeError = context.getString(
                                        R.string.time_block_revoke_failed
                                    )
                                    reloadSessions()
                                }
                            }
                            revoking = false
                        }
                    }
                ) {
                    if (revoking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.time_block_revoke_action))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !revoking,
                    onClick = {
                        sessionToRevoke = null
                        masterPassword = ""
                        revokeError = null
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, color = TextPrimary, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.action_back),
                            tint = TextPrimary
                        )
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
                Box(
                    Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AccentCyan)
                }
            } else if (sessions.isEmpty()) {
                EmptySessionCard(sessionType)
            } else {
                sessions.forEach { session ->
                    SessionDetailCard(
                        session = session,
                        canRevokeWithMasterPassword =
                            session.sessionType == "TIME" &&
                                timedProtection.isProtectedSession(session.id),
                        onRevoke = {
                            masterPassword = ""
                            revokeError = null
                            sessionToRevoke = session
                        }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onAddNewBlock,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = DarkBg)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.session_detail_add_new),
                    color = DarkBg,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
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
fun SessionDetailCard(
    session: BlockSession,
    canRevokeWithMasterPassword: Boolean = false,
    onRevoke: () -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
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
                        if (session.sessionType == "PASSWORD") {
                            stringResource(R.string.session_detail_password_session)
                        } else {
                            stringResource(R.string.session_detail_time_session)
                        },
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        if (session.isFixed24h) {
                            stringResource(R.string.session_detail_fixed_24h)
                        } else {
                            stringResource(R.string.session_detail_scheduled)
                        },
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
                        stringResource(
                            R.string.session_detail_expires_in,
                            String.format(Locale.US, "%dd %dh %dm", days, hours, mins)
                        ),
                        color = AccentCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (canRevokeWithMasterPassword) {
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(
                    onClick = onRevoke,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(R.string.time_block_revoke_action),
                        color = DangerRed,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(modifier = Modifier.height(16.dp))
                    Divider(color = CardBorder)
                    Spacer(modifier = Modifier.height(16.dp))

                    if (apps.isNotEmpty()) {
                        Text(
                            stringResource(R.string.session_detail_apps_blocked),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentPurple
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        apps.forEach { pkg -> AppItemRow(pkg) }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    if (sites.isNotEmpty()) {
                        Text(
                            stringResource(R.string.session_detail_sites_blocked),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentCyan
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        sites.forEach { site -> SiteItemRow(site) }
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
            } catch (_: Exception) {
                // Keep package name fallback.
            }
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
        Icon(
            Icons.Default.Public,
            null,
            Modifier.size(24.dp),
            tint = AccentCyan.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(WebsiteBlocker.displayRule(domain), fontSize = 14.sp, color = TextSecondary)
    }
}
