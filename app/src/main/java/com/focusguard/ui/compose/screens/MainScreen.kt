package com.focusguard.ui.compose.screens

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.security.AuthManager
import com.focusguard.ui.compose.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    permissionsVisible: Boolean,
    onPermissionsClick: () -> Unit,
    onPasswordSessionClick: () -> Unit,
    onTimeSessionClick: () -> Unit,
    onActiveSessionsClick: () -> Unit,
    onDeviceOwnerClick: () -> Unit,
    onLimitsClick: () -> Unit,
    onIntruderLogClick: () -> Unit,
    onLanguageClick: () -> Unit,
    onPasswordManagementClick: () -> Unit,
    onAppUsageLimitsClick: () -> Unit,
    authManager: AuthManager,
    usageStatsContent: @Composable () -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = DarkSurface,
                modifier = Modifier.width(300.dp)
            ) {
                // Header
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(AccentCyan.copy(alpha = 0.2f), AccentPurple.copy(alpha = 0.2f))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(painter = painterResource(id = R.drawable.ic_shield), contentDescription = null, tint = AccentCyan, modifier = Modifier.size(28.dp))
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text("FocusGuard", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text("Proteção Digital", fontSize = 12.sp, color = TextHint)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Divider(color = CardBorder, modifier = Modifier.padding(horizontal = 20.dp))
                Spacer(modifier = Modifier.height(16.dp))

                // Menu Items
                DrawerMenuButton(
                    icon = Icons.Default.Lock,
                    label = stringResource(id = R.string.manage_passwords),
                    iconTint = AccentCyan,
                    onClick = {
                        onPasswordManagementClick()
                        scope.launch { drawerState.close() }
                    }
                )
                DrawerMenuButton(
                    icon = Icons.Default.Security,
                    label = stringResource(id = R.string.limits_and_security),
                    iconTint = AccentCyan,
                    onClick = {
                        onLimitsClick()
                        scope.launch { drawerState.close() }
                    }
                )
                DrawerMenuButton(
                    icon = Icons.Default.PhotoCamera,
                    label = stringResource(id = R.string.intruder_log),
                    iconTint = AccentCyan,
                    onClick = {
                        onIntruderLogClick()
                        scope.launch { drawerState.close() }
                    }
                )
                DrawerMenuButton(
                    icon = Icons.Default.Language,
                    label = stringResource(id = R.string.language_settings),
                    iconTint = AccentCyan,
                    onClick = {
                        onLanguageClick()
                        scope.launch { drawerState.close() }
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = CardBorder, modifier = Modifier.padding(horizontal = 20.dp))
                Spacer(modifier = Modifier.height(12.dp))

                DrawerMenuButton(
                    icon = Icons.Default.Warning,
                    label = stringResource(id = R.string.nuclear_protection),
                    iconTint = DangerRed,
                    labelColor = DangerRed,
                    bgColor = DangerRed.copy(alpha = 0.08f),
                    onClick = {
                        onDeviceOwnerClick()
                        scope.launch { drawerState.close() }
                    }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("FocusGuard", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = DarkBg,
                        titleContentColor = TextPrimary,
                        navigationIconContentColor = TextPrimary
                    )
                )
            }
        ) { paddingValues ->
            val pagerState = rememberPagerState(pageCount = { 2 })

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DarkBg)
                    .padding(paddingValues)
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f)
                ) { page ->
                    when (page) {
                        0 -> HomeContent(
                            permissionsVisible = permissionsVisible,
                            onPermissionsClick = onPermissionsClick,
                            onPasswordSessionClick = onPasswordSessionClick,
                            onTimeSessionClick = onTimeSessionClick,
                            onActiveSessionsClick = onActiveSessionsClick,
                            onAppUsageLimitsClick = onAppUsageLimitsClick,
                            pagerHint = true
                        )
                        1 -> usageStatsContent()
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    repeat(2) { index ->
                        val isSelected = pagerState.currentPage == index
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .size(if (isSelected) 8.dp else 6.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) AccentCyan else TextHint.copy(alpha = 0.4f))
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrawerMenuButton(
    icon: ImageVector,
    label: String,
    iconTint: Color = AccentCyan,
    labelColor: Color = TextPrimary,
    bgColor: Color = DarkCard,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconTint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(14.dp))
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = labelColor)
            Spacer(modifier = Modifier.weight(1f))
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextHint, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun HomeContent(
    permissionsVisible: Boolean,
    onPermissionsClick: () -> Unit,
    onPasswordSessionClick: () -> Unit,
    onTimeSessionClick: () -> Unit,
    onActiveSessionsClick: () -> Unit,
    onAppUsageLimitsClick: () -> Unit,
    pagerHint: Boolean
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(100)
        visible = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 0.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AnimatedVisibility(
            visible = permissionsVisible,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically()
        ) {
            Button(
                onClick = onPermissionsClick,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(id = R.string.give_permissions), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(600)) + scaleIn(animationSpec = tween(600))
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_shield),
                contentDescription = "FocusGuard",
                modifier = Modifier.size(64.dp),
                tint = AccentCyan
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(600, delayMillis = 100)) + slideInVertically(animationSpec = tween(600, delayMillis = 100)) { it / 2 }
        ) {
            Text("FocusGuard", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        }

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(600, delayMillis = 200)) + slideInVertically(animationSpec = tween(600, delayMillis = 200)) { it / 2 }
        ) {
            Text(stringResource(id = R.string.focus_subtitle), fontSize = 14.sp, color = TextSecondary, modifier = Modifier.padding(top = 4.dp, bottom = 32.dp))
        }

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(500, delayMillis = 300)) + slideInVertically(animationSpec = tween(500, delayMillis = 300)) { it / 3 }
        ) {
            SessionCard(
                icon = Icons.Outlined.Lock,
                title = stringResource(id = R.string.password_block),
                subtitle = stringResource(id = R.string.password_block_sub),
                onClick = onPasswordSessionClick
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(500, delayMillis = 400)) + slideInVertically(animationSpec = tween(500, delayMillis = 400)) { it / 3 }
        ) {
            SessionCard(
                icon = Icons.Outlined.Timer,
                title = stringResource(id = R.string.time_block),
                subtitle = stringResource(id = R.string.time_block_sub),
                onClick = onTimeSessionClick
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(500, delayMillis = 500)) + slideInVertically(animationSpec = tween(500, delayMillis = 500)) { it / 3 }
        ) {
            SessionCard(
                icon = Icons.Outlined.DataUsage,
                title = "Limites de Uso",
                subtitle = "Defina tempo máximo diário para apps e sites",
                onClick = onAppUsageLimitsClick
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Divider(color = Divider, thickness = 1.dp)

        Spacer(modifier = Modifier.height(24.dp))

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(500, delayMillis = 500))
        ) {
            OutlinedButton(
                onClick = onActiveSessionsClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.5.dp, AccentCyan),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentCyan)
            ) {
                Icon(Icons.Outlined.PlaylistAddCheck, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(id = R.string.view_active_sessions), fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        if (pagerHint) {
            Text(stringResource(id = R.string.swipe_hint), fontSize = 12.sp, color = TextHint, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(colors = listOf(AccentCyan.copy(alpha = 0.15f), AccentPurple.copy(alpha = 0.15f)))),
                contentAlignment = Alignment.Center
            ) { Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp), tint = AccentCyan) }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(modifier = Modifier.height(4.dp))
                Text(subtitle, fontSize = 13.sp, color = TextSecondary)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = "Abrir", modifier = Modifier.size(20.dp), tint = TextHint)
        }
    }
}
