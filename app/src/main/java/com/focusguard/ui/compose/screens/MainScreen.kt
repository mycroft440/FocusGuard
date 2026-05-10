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
    onPasswordSessionClick: () -> Unit,
    onTimeSessionClick: () -> Unit,
    onAppUsageLimitsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    usageStatsContent: @Composable () -> Unit,
    pomodoroContent: @Composable () -> Unit
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
                    selected = selectedTab == 0,
                    onClick = { onTabChange(0) },
                    icon = { Icon(Icons.Default.BarChart, contentDescription = stringResource(R.string.nav_insights)) },
                    label = { Text(stringResource(R.string.nav_insights)) },
                    colors = navigationItemColors()
                )
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
                    if (targetState > initialState) {
                        slideInHorizontally { width -> width } + fadeIn() togetherWith
                                slideOutHorizontally { width -> -width } + fadeOut()
                    } else {
                        slideInHorizontally { width -> -width } + fadeIn() togetherWith
                                slideOutHorizontally { width -> width } + fadeOut()
                    }.using(SizeTransform(clip = false))
                },
                label = stringResource(R.string.maincontent)
            ) { targetTab ->
                when (targetTab) {
                    0 -> usageStatsContent()
                    1 -> HomeContent(
                        permissionsVisible = permissionsVisible,
                        onPermissionsClick = onPermissionsClick,
                        onPasswordSessionClick = onPasswordSessionClick,
                        onTimeSessionClick = onTimeSessionClick,
                        onAppUsageLimitsClick = onAppUsageLimitsClick,
                        pagerHint = false
                    )
                    2 -> pomodoroContent()
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
    onPasswordSessionClick: () -> Unit,
    onTimeSessionClick: () -> Unit,
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
        Spacer(modifier = Modifier.height(8.dp))

        // [F2] Agrupamento de animações do Header para reduzir overhead de RenderNode
        AnimatedVisibility(
            visible = visible,
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

        AnimatedVisibility(
            visible = permissionsVisible,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Card(
                onClick = onPermissionsClick,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
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
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(500, delayMillis = 150)) + slideInVertically(animationSpec = tween(500, delayMillis = 150)) { 30 }
        ) {
            Column {
                SessionCard(
                    icon = Icons.Outlined.Lock,
                    title = stringResource(id = R.string.password_block),
                    subtitle = stringResource(id = R.string.password_block_sub),
                    onClick = onPasswordSessionClick
                )

                Spacer(modifier = Modifier.height(12.dp))

                SessionCard(
                    icon = Icons.Outlined.Timer,
                    title = stringResource(id = R.string.time_block),
                    subtitle = stringResource(id = R.string.time_block_sub),
                    onClick = onTimeSessionClick
                )

                Spacer(modifier = Modifier.height(12.dp))

                SessionCard(
                    icon = Icons.Outlined.HourglassEmpty,
                    title = stringResource(id = R.string.usage_limits),
                    subtitle = stringResource(id = R.string.usage_limits_sub),
                    onClick = onAppUsageLimitsClick
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (pagerHint) {
            Text(stringResource(id = R.string.swipe_hint), fontSize = 12.sp, color = TextHint, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = stringResource(R.string.scaleanimation)
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
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(colors = listOf(AccentCyan.copy(alpha = 0.15f), AccentPurple.copy(alpha = 0.15f)))),
                contentAlignment = Alignment.Center
            ) { Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp), tint = AccentCyan) }
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