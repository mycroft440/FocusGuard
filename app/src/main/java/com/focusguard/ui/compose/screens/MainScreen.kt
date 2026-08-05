package com.focusguard.ui.compose.screens

import kotlin.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.ui.compose.theme.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    selectedTab: Int,
    onTabChange: (Int) -> Unit,
    permissionsVisible: Boolean,
    onPermissionsClick: () -> Unit,
    onBlockTypeClick: (BlockTypeUi) -> Unit,
    onSettingsClick: () -> Unit,
    usageStatsContent: @Composable () -> Unit,
    pomodoroContent: @Composable () -> Unit,
    recoveryContent: @Composable () -> Unit
) {
    

    Scaffold(
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .height(64.dp)
            ) {
                Card(
                    onClick = { onTabChange(0) },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                    border = BorderStroke(1.dp, CardBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Public,
                            contentDescription = null,
                            tint = AccentCyan,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.nav_metrics),
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                IconButton(
                    onClick = onSettingsClick,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp)
                ) {
                    Icon(
                        Icons.Default.Menu,
                        contentDescription = stringResource(R.string.nav_settings),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { onTabChange(1) },
                    icon = { Icon(Icons.Default.Shield, contentDescription = stringResource(R.string.nav_protection)) },
                    label = { Text(stringResource(R.string.nav_protection)) },
                    colors = navigationItemColors()
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { onTabChange(2) },
                    icon = { Icon(Icons.Default.Timer, contentDescription = stringResource(R.string.nav_focus)) },
                    label = { Text(stringResource(R.string.nav_focus)) },
                    colors = navigationItemColors()
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { onTabChange(3) },
                    icon = {
                        Icon(
                            Icons.Outlined.VisibilityOff,
                            contentDescription = stringResource(R.string.nav_recovery)
                        )
                    },
                    label = { Text(stringResource(R.string.nav_recovery)) },
                    colors = navigationItemColors()
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(paddingValues)
        ) {
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    fadeIn(animationSpec = tween(180)) togetherWith
                        fadeOut(animationSpec = tween(180))
                },
                label = "MainContent"
            ) { targetTab ->
                when (targetTab) {
                    0 -> usageStatsContent()
                    1 -> HomeContent(
                        permissionsVisible = permissionsVisible,
                        onPermissionsClick = onPermissionsClick,
                        onBlockTypeClick = onBlockTypeClick,
                        pagerHint = false
                    )
                    2 -> pomodoroContent()
                    3 -> recoveryContent()
                }
            }
        }
    }
}

@Composable
private fun navigationItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = AccentCyan,
    selectedTextColor = AccentCyan,
    unselectedIconColor = TextHint,
    unselectedTextColor = TextHint,
    indicatorColor = AccentCyan.copy(alpha = 0.1f)
)

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
            Icon(Icons.Default.ChevronRight, contentDescription = stringResource(R.string.action_open), tint = TextHint, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun HomeContent(
    permissionsVisible: Boolean,
    onPermissionsClick: () -> Unit,
    onBlockTypeClick: (BlockTypeUi) -> Unit,
    pagerHint: Boolean
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(100)
        visible = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
    ) {
        // [F2] Agrupamento de animações do Header para reduzir overhead de RenderNode
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp),
            enter = fadeIn(animationSpec = tween(450)) + slideInVertically(animationSpec = tween(450)) { -20 }
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_shield),
                    contentDescription = stringResource(R.string.content_focusguard_logo),
                    modifier = Modifier.size(48.dp),
                    tint = AccentCyan
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(id = R.string.app_name), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                Text(stringResource(id = R.string.focus_subtitle), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, bottom = 20.dp))
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedVisibility(
                visible = permissionsVisible,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Card(
                    onClick = onPermissionsClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DangerRed.copy(alpha = 0.1f)),
                    border = BorderStroke(1.dp, DangerRed)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = stringResource(R.string.content_warning), tint = DangerRed)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(id = R.string.pending_permissions_title), color = DangerRed, fontWeight = FontWeight.Bold)
                            Text(stringResource(id = R.string.pending_permissions_desc), color = DangerRed.copy(alpha = 0.8f), fontSize = 12.sp)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = stringResource(R.string.action_open), tint = DangerRed)
                    }
                }
            }

            // [F2] Agrupamento de cards de funcionalidade
            //
            // Um card por tipo de proteção, em vez de uma entrada única: as três
            // se comportam de formas muito diferentes — uma abre com senha, uma
            // limita por dia, uma não abre de jeito nenhum até o prazo acabar — e
            // escolher entre elas dentro de um assistente escondia essa diferença
            // justamente de quem ainda não a conhece.
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(500, delayMillis = 150)) + slideInVertically(animationSpec = tween(500, delayMillis = 150)) { 30 }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    BlockTypeUi.entries.forEach { type ->
                        SessionCard(
                            icon = type.icon,
                            title = stringResource(id = type.titleRes),
                            subtitle = stringResource(id = type.subtitleRes),
                            accent = type.accent,
                            onClick = { onBlockTypeClick(type) }
                        )
                    }
                }
            }
        }

        if (pagerHint) {
            Text(
                stringResource(id = R.string.swipe_hint),
                fontSize = 12.sp,
                color = TextHint,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    // Cor própria por tipo de proteção, para o card ser reconhecível antes de o
    // texto ser lido. O padrão mantém o visual das chamadas antigas.
    accent: Color = AccentCyan
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "ScaleAnimation"
    )

    Card(
        onClick = { 
            isPressed = true
            onClick()
        },
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(colors = listOf(accent.copy(alpha = 0.18f), accent.copy(alpha = 0.06f)))),
                contentAlignment = Alignment.Center
            ) { Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp), tint = accent) }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(modifier = Modifier.height(3.dp))
                Text(subtitle, fontSize = 13.sp, color = TextSecondary)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = stringResource(R.string.action_open), modifier = Modifier.size(20.dp), tint = TextHint)
        }
    }
    
    // Reset pressed state after a delay or interaction
    LaunchedEffect(isPressed) {
        if (isPressed) {
            kotlinx.coroutines.delay(100)
            isPressed = false
        }
    }
}
