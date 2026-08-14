package com.focusguard.ui.compose.screens

import kotlin.OptIn
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.data.UserProfile
import com.focusguard.security.ProtectionPermission
import com.focusguard.ui.compose.theme.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    profile: UserProfile,
    selectedTab: Int,
    onTabChange: (Int) -> Unit,
    focusModeActive: Boolean,
    missingProtectionPermissions: List<ProtectionPermission>,
    showCreatorInstagramCard: Boolean,
    showCreatorFeedbackButton: Boolean,
    onPermissionsClick: () -> Unit,
    onCreatorInstagramClick: () -> Unit,
    onBlockTypeClick: (BlockTypeUi) -> Unit,
    onSettingsClick: () -> Unit,
    usageStatsContent: @Composable () -> Unit,
    pomodoroContent: @Composable () -> Unit,
    recoveryContent: @Composable () -> Unit,
    focusModeContent: @Composable () -> Unit
) {
    val settingsContentDescription = if (profile.isConfigured) {
        stringResource(R.string.profile_settings_content_description, profile.displayName)
    } else {
        stringResource(R.string.nav_settings)
    }

    BackHandler(enabled = focusModeActive) {
        if (selectedTab != 4) onTabChange(4)
    }

    Scaffold(
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .height(64.dp)
            ) {
                if (selectedTab == 4) {
                    Text(
                        stringResource(R.string.nav_focus_mode),
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 16.dp),
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                } else if (!focusModeActive) {
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
                }

                IconButton(
                    onClick = onSettingsClick,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp)
                        .semantics {
                            contentDescription = settingsContentDescription
                        }
                ) {
                    if (profile.isConfigured) {
                        ProfileAvatar(
                            avatarId = profile.avatarId,
                            modifier = Modifier.size(38.dp)
                        )
                    } else {
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (!focusModeActive) {
                FocusGuardBottomNavigation(
                    selectedTab = selectedTab,
                    onTabChange = onTabChange
                )
            }
        }
    ) { paddingValues ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(paddingValues)
        ) {
            if (focusModeActive) {
                FocusModeNavigationRail(
                    selectedTab = selectedTab,
                    onTabChange = onTabChange
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
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
                            missingProtectionPermissions = missingProtectionPermissions,
                            showCreatorInstagramCard = showCreatorInstagramCard,
                            showCreatorFeedbackButton = showCreatorFeedbackButton,
                            onPermissionsClick = onPermissionsClick,
                            onCreatorInstagramClick = onCreatorInstagramClick,
                            onBlockTypeClick = onBlockTypeClick,
                            pagerHint = false
                        )
                        2 -> pomodoroContent()
                        3 -> recoveryContent()
                        4 -> focusModeContent()
                    }
                }
            }
        }
    }
}

@Composable
private fun FocusGuardBottomNavigation(
    selectedTab: Int,
    onTabChange: (Int) -> Unit
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp
    ) {
        focusGuardNavigationItems().forEach { item ->
            NavigationBarItem(
                selected = selectedTab == item.tab,
                onClick = { onTabChange(item.tab) },
                icon = {
                    Icon(
                        item.icon,
                        contentDescription = stringResource(item.labelRes)
                    )
                },
                label = { Text(stringResource(item.labelRes)) },
                colors = navigationItemColors()
            )
        }
    }
}

@Composable
private fun FocusModeNavigationRail(
    selectedTab: Int,
    onTabChange: (Int) -> Unit
) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surface,
        header = {
            Icon(
                Icons.Default.LockClock,
                contentDescription = stringResource(R.string.nav_focus_mode),
                tint = AccentCyan,
                modifier = Modifier.padding(vertical = 12.dp).size(28.dp)
            )
        }
    ) {
        focusGuardNavigationItems().forEach { item ->
            NavigationRailItem(
                selected = selectedTab == item.tab,
                onClick = { onTabChange(item.tab) },
                icon = {
                    Icon(
                        item.icon,
                        contentDescription = stringResource(item.labelRes)
                    )
                },
                label = { Text(stringResource(item.labelRes), fontSize = 10.sp) },
                alwaysShowLabel = true,
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = AccentCyan,
                    selectedTextColor = AccentCyan,
                    unselectedIconColor = TextHint,
                    unselectedTextColor = TextHint,
                    indicatorColor = AccentCyan.copy(alpha = 0.1f)
                )
            )
        }
    }
}

private data class FocusGuardNavigationItem(
    val tab: Int,
    val icon: ImageVector,
    val labelRes: Int
)

private fun focusGuardNavigationItems() = listOf(
    FocusGuardNavigationItem(1, Icons.Default.Shield, R.string.nav_protection),
    FocusGuardNavigationItem(2, Icons.Default.Timer, R.string.nav_focus),
    FocusGuardNavigationItem(3, Icons.Outlined.VisibilityOff, R.string.nav_recovery),
    FocusGuardNavigationItem(4, Icons.Default.LockClock, R.string.nav_focus_mode)
)

internal fun pendingPermissionsDescriptionRes(
    missingPermissions: List<ProtectionPermission>
): Int {
    return when (missingPermissions.toSet()) {
        setOf(ProtectionPermission.SELF_PROTECTION_CONSENT) ->
            R.string.pending_self_protection_consent_desc
        setOf(ProtectionPermission.ACCESSIBILITY) ->
            R.string.pending_permissions_accessibility_desc
        setOf(ProtectionPermission.USAGE_ACCESS) ->
            R.string.pending_permissions_usage_access_desc
        else -> R.string.pending_permissions_desc
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
    missingProtectionPermissions: List<ProtectionPermission>,
    showCreatorInstagramCard: Boolean,
    showCreatorFeedbackButton: Boolean,
    onPermissionsClick: () -> Unit,
    onCreatorInstagramClick: () -> Unit,
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    top = 8.dp,
                    bottom = if (pagerHint) 64.dp else 16.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Cabeçalho e cards compartilham o mesmo fluxo vertical. Isso evita
            // sobreposição quando a fonte do sistema ou a tela forem maiores.
            AnimatedVisibility(
                visible = visible,
                modifier = Modifier.fillMaxWidth(),
                enter = fadeIn(animationSpec = tween(450)) +
                    slideInVertically(animationSpec = tween(450)) { -20 }
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_shield),
                        contentDescription = stringResource(R.string.content_focusguard_logo),
                        modifier = Modifier.size(38.dp),
                        tint = AccentCyan
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(id = R.string.app_name),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        stringResource(id = R.string.focus_subtitle),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 1.dp, bottom = 18.dp)
                    )
                }
            }

            AnimatedVisibility(
                visible = visible && missingProtectionPermissions.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Card(
                    onClick = onPermissionsClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DangerRed.copy(alpha = 0.1f)),
                    border = BorderStroke(1.dp, DangerRed)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = stringResource(R.string.content_warning),
                            tint = DangerRed,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(id = R.string.pending_permissions_title), color = DangerRed, fontWeight = FontWeight.Bold)
                            Text(
                                stringResource(
                                    id = pendingPermissionsDescriptionRes(
                                        missingProtectionPermissions
                                    )
                                ),
                                color = DangerRed.copy(alpha = 0.8f),
                                fontSize = 12.sp
                            )
                        }
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = stringResource(R.string.action_open),
                            tint = DangerRed,
                            modifier = Modifier.size(20.dp)
                        )
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
                modifier = Modifier.fillMaxWidth(),
                enter = fadeIn(animationSpec = tween(500, delayMillis = 150)) + slideInVertically(animationSpec = tween(500, delayMillis = 150)) { 30 }
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    BlockTypeUi.entries.forEach { type ->
                        SessionCard(
                            icon = type.icon,
                            title = stringResource(id = type.titleRes),
                            subtitle = stringResource(id = type.subtitleRes),
                            accent = type.accent,
                            compact = true,
                            onClick = { onBlockTypeClick(type) }
                        )
                    }
                    AnimatedVisibility(
                        visible = showCreatorInstagramCard,
                        modifier = Modifier.fillMaxWidth(),
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        CreatorInstagramCard(
                            onClick = onCreatorInstagramClick,
                            compact = true
                        )
                    }
                    AnimatedVisibility(
                        visible = showCreatorFeedbackButton,
                        modifier = Modifier.fillMaxWidth(),
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        CreatorFeedbackButton(
                            onClick = onCreatorInstagramClick,
                            compact = true
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
private fun CreatorInstagramCard(
    onClick: () -> Unit,
    compact: Boolean = false
) {
    val instagramGradient = Brush.linearGradient(
        colors = listOf(
            Color(0xFF833AB4),
            Color(0xFFE1306C),
            Color(0xFFF77737),
            Color(0xFFFCAF45)
        )
    )

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = BorderStroke(1.dp, instagramGradient)
    ) {
        Row(
            modifier = Modifier.padding(if (compact) 10.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(if (compact) 40.dp else 52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(instagramGradient),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(if (compact) 22.dp else 27.dp)
                )
            }
            Spacer(Modifier.width(if (compact) 10.dp else 14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.creator_instagram_title),
                    color = TextPrimary,
                    fontSize = if (compact) 13.sp else 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.creator_instagram_handle),
                    color = Color(0xFFE1306C),
                    fontSize = if (compact) 11.sp else 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(if (compact) 2.dp else 4.dp))
                Text(
                    text = stringResource(R.string.creator_instagram_description),
                    color = TextSecondary,
                    fontSize = if (compact) 11.sp else 12.sp,
                    lineHeight = if (compact) 14.sp else 16.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = stringResource(R.string.action_open),
                tint = Color(0xFFE1306C),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreatorFeedbackButton(
    onClick: () -> Unit,
    compact: Boolean = false
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (compact) 11.dp else 16.dp,
                vertical = if (compact) 9.dp else 14.dp
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(if (compact) 36.dp else 42.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(AccentCyan.copy(alpha = 0.09f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lightbulb,
                    contentDescription = null,
                    tint = AccentCyan.copy(alpha = 0.82f),
                    modifier = Modifier.size(if (compact) 19.dp else 22.dp)
                )
            }
            Spacer(Modifier.width(if (compact) 10.dp else 13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.creator_feedback_title),
                    color = TextPrimary,
                    fontSize = if (compact) 13.sp else 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(if (compact) 1.dp else 3.dp))
                Text(
                    text = stringResource(R.string.creator_feedback_description),
                    color = TextSecondary,
                    fontSize = if (compact) 11.sp else 12.sp,
                    lineHeight = if (compact) 14.sp else 16.sp
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = stringResource(R.string.action_open),
                tint = TextHint,
                modifier = Modifier.size(18.dp)
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
    accent: Color = AccentCyan,
    compact: Boolean = false
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
        Row(
            modifier = Modifier.padding(
                horizontal = if (compact) 13.dp else 16.dp,
                vertical = if (compact) 12.dp else 16.dp
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(if (compact) 44.dp else 48.dp)
                    .clip(RoundedCornerShape(if (compact) 13.dp else 16.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                accent.copy(alpha = 0.18f),
                                accent.copy(alpha = 0.06f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(if (compact) 24.dp else 26.dp),
                    tint = accent
                )
            }
            Spacer(modifier = Modifier.width(if (compact) 12.dp else 16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = if (compact) 15.sp else 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(if (compact) 2.dp else 3.dp))
                Text(
                    subtitle,
                    fontSize = if (compact) 12.sp else 13.sp,
                    lineHeight = if (compact) 15.sp else 17.sp,
                    color = TextSecondary
                )
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
