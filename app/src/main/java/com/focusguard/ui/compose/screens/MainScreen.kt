package com.focusguard.ui.compose.screens

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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.ui.compose.theme.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    permissionsVisible: Boolean,
    onPermissionsClick: () -> Unit,
    onTimeSessionClick: () -> Unit,
    onRecurringSessionClick: () -> Unit,
    onActiveSessionsClick: () -> Unit,
    onDeviceOwnerClick: () -> Unit,
    usageStatsContent: @Composable () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { 2 })

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f)
        ) { page ->
            when (page) {
                0 -> HomeContent(
                    permissionsVisible = permissionsVisible,
                    onPermissionsClick = onPermissionsClick,
                    onTimeSessionClick = onTimeSessionClick,
                    onRecurringSessionClick = onRecurringSessionClick,
                    onActiveSessionsClick = onActiveSessionsClick,
                    onDeviceOwnerClick = onDeviceOwnerClick,
                    pagerHint = true
                )
                1 -> usageStatsContent()
            }
        }

        // Page indicator
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
                        .background(
                            if (isSelected) AccentCyan else TextHint.copy(alpha = 0.4f)
                        )
                )
            }
        }
    }
}

@Composable
fun HomeContent(
    permissionsVisible: Boolean,
    onPermissionsClick: () -> Unit,
    onTimeSessionClick: () -> Unit,
    onRecurringSessionClick: () -> Unit,
    onActiveSessionsClick: () -> Unit,
    onDeviceOwnerClick: () -> Unit,
    pagerHint: Boolean
) {
    // Staggered animation
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
        // Permission banner
        AnimatedVisibility(
            visible = permissionsVisible,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically()
        ) {
            Button(
                onClick = onPermissionsClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DangerRed
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Dê o restante das permissões aqui",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Shield icon
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

        // Title
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(600, delayMillis = 100))
                    + slideInVertically(animationSpec = tween(600, delayMillis = 100)) { it / 2 }
        ) {
            Text(
                text = "FocusGuard",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(600, delayMillis = 200))
                    + slideInVertically(animationSpec = tween(600, delayMillis = 200)) { it / 2 }
        ) {
            Text(
                text = "Foco Total, Zero Distrações",
                fontSize = 14.sp,
                color = TextSecondary,
                modifier = Modifier.padding(top = 4.dp, bottom = 32.dp)
            )
        }

        // Card: Time Session
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(500, delayMillis = 300))
                    + slideInVertically(animationSpec = tween(500, delayMillis = 300)) { it / 3 }
        ) {
            SessionCard(
                icon = Icons.Outlined.Timer,
                title = "Sessão por Tempo",
                subtitle = "Defina dias e horas para focar",
                onClick = onTimeSessionClick
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Card: Recurring Session
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(500, delayMillis = 400))
                    + slideInVertically(animationSpec = tween(500, delayMillis = 400)) { it / 3 }
        ) {
            SessionCard(
                icon = Icons.Outlined.CalendarMonth,
                title = "Sessão Recorrente",
                subtitle = "Agende horários fixos na semana",
                onClick = onRecurringSessionClick
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Divider
        Divider(
            color = Divider,
            thickness = 1.dp
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Active sessions button
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
                Icon(
                    Icons.Outlined.PlaylistAddCheck,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Ver Sessões Ativas", fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Nuclear protection button
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(500, delayMillis = 600))
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = onDeviceOwnerClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DangerRed
                    ),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp)
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("PROTEÇÃO NUCLEAR", fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Ative para que o bloqueio seja impossivel de burlar!!",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Swipe hint
        if (pagerHint) {
            Text(
                text = "← Deslize para ver estatísticas →",
                fontSize = 12.sp,
                color = TextHint,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
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
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = DarkCard
        ),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(AccentCyan.copy(alpha = 0.15f), AccentPurple.copy(alpha = 0.15f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = AccentCyan
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            }

            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = "Abrir",
                modifier = Modifier.size(20.dp),
                tint = TextHint
            )
        }
    }
}
