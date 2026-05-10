package com.focusguard.ui.compose.screens

import kotlin.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.platform.LocalContext
import android.graphics.drawable.Drawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import com.focusguard.ui.compose.theme.*
import com.focusguard.R

data class SelectableAppUi(
    val packageName: String,
    val appName: String,
    val isSelected: Boolean,
    val isSuggested: Boolean = false,
    val isInstalled: Boolean = true,
    val category: String = "",
    val iconUrl: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSelectionScreen(
    apps: List<SelectableAppUi>,
    isLoading: Boolean,
    onToggleApp: (String) -> Unit,
    onBack: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var expandUninstalled by remember { mutableStateOf(false) }
    var expandInstalled by remember { mutableStateOf(false) }

    val filteredApps = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) apps
        else apps.filter {
            it.appName.contains(searchQuery, ignoreCase = true) ||
            it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    val installedApps = remember(filteredApps) { filteredApps.filter { it.isInstalled } }
    val uninstalledApps = remember(filteredApps) { filteredApps.filter { !it.isInstalled } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_selection_title), color = TextPrimary) },
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
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.limits_search_placeholder), color = TextHint) },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = TextHint)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentCyan,
                    cursorColor = AccentCyan,
                    unfocusedTextColor = TextPrimary,
                    focusedTextColor = TextPrimary
                ),
                shape = RoundedCornerShape(16.dp),
                singleLine = true
            )

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AccentCyan)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    if (uninstalledApps.isNotEmpty()) {
                        item {
                            Text(
                                text = "Aplicativos e Sites predefinidos:",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = AccentCyan,
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                            )
                        }

                        val visibleUninstalled = if (expandUninstalled || searchQuery.isNotBlank()) uninstalledApps else uninstalledApps.take(3)

                        items(visibleUninstalled, key = { it.packageName }) { app ->
                            AppSelectionItem(app = app, onToggle = { onToggleApp(app.packageName) })
                        }

                        if (!expandUninstalled && searchQuery.isBlank() && uninstalledApps.size > 3) {
                            item {
                                TextButton(
                                    onClick = { expandUninstalled = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.app_selection_show_more, uninstalledApps.size - 3), color = AccentCyan)
                                }
                            }
                        }

                        if (expandUninstalled && searchQuery.isBlank()) {
                            item {
                                TextButton(
                                    onClick = { expandUninstalled = false },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.app_selection_show_less), color = AccentCyan)
                                }
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider(color = CardBorder, thickness = 1.dp)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.app_selection_installed),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                            )
                        }

                        val visibleInstalled = if (expandInstalled || searchQuery.isNotBlank()) installedApps else installedApps.take(3)

                        items(visibleInstalled, key = { it.packageName }) { app ->
                            AppSelectionItem(app = app, onToggle = { onToggleApp(app.packageName) })
                        }

                        if (!expandInstalled && searchQuery.isBlank() && installedApps.size > 3) {
                            item {
                                TextButton(
                                    onClick = { expandInstalled = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.app_selection_show_more, installedApps.size - 3), color = TextPrimary.copy(alpha = 0.7f))
                                }
                            }
                        }

                        if (expandInstalled && searchQuery.isBlank()) {
                            item {
                                TextButton(
                                    onClick = { expandInstalled = false },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.app_selection_show_less), color = TextPrimary.copy(alpha = 0.7f))
                                }
                            }
                        }
                    } else {
                        item {
                            Text(
                                text = stringResource(R.string.app_selection_installed),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                            )
                        }

                        val visibleInstalled = if (expandInstalled || searchQuery.isNotBlank()) installedApps else installedApps.take(3)

                        items(visibleInstalled, key = { it.packageName }) { app ->
                            AppSelectionItem(app = app, onToggle = { onToggleApp(app.packageName) })
                        }

                        if (!expandInstalled && searchQuery.isBlank() && installedApps.size > 3) {
                            item {
                                TextButton(
                                    onClick = { expandInstalled = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.app_selection_show_more, installedApps.size - 3), color = TextPrimary.copy(alpha = 0.7f))
                                }
                            }
                        }

                        if (expandInstalled && searchQuery.isBlank()) {
                            item {
                                TextButton(
                                    onClick = { expandInstalled = false },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.app_selection_show_less), color = TextPrimary.copy(alpha = 0.7f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppSelectionItem(
    app: SelectableAppUi,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clickable { onToggle() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (app.isSelected) AccentCyan.copy(alpha = 0.08f) else DarkCard
        ),
        border = BorderStroke(1.dp, if (app.isSelected) AccentCyan.copy(alpha = 0.3f) else CardBorder)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val context = androidx.compose.ui.platform.LocalContext.current
            var iconDrawable by remember(app.packageName) { mutableStateOf<Drawable?>(null) }

            if (app.isInstalled) {
                LaunchedEffect(app.packageName) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            iconDrawable = context.packageManager.getApplicationIcon(app.packageName)
                        } catch (_: Exception) {}
                    }
                }
            }

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (iconDrawable != null) {
                    val bitmap = remember(iconDrawable) {
                        iconDrawable!!.toBitmap(80, 80).asImageBitmap()
                    }
                    Image(
                        bitmap = bitmap,
                        contentDescription = app.appName,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (!app.iconUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = app.iconUrl,
                        contentDescription = app.appName,
                        modifier = Modifier.fillMaxSize(),
                        error = androidx.compose.ui.graphics.painter.ColorPainter(
                            Color(app.packageName.hashCode()).copy(alpha = 1.0f)
                        )
                    )
                } else {
                    val brandColor = remember(app.packageName) {
                        when {
                            app.packageName.contains("instagram") -> Color(0xFFE4405F)
                            app.packageName.contains("facebook") -> Color(0xFF1877F2)
                            app.packageName.contains("youtube") -> Color(0xFFFF0000)
                            app.packageName.contains("tiktok") -> Color(0xFF000000)
                            app.packageName.contains("twitter") || app.packageName.contains("x.android") -> Color(0xFF1DA1F2)
                            app.packageName.contains("netflix") -> Color(0xFFE50914)
                            app.packageName.contains("spotify") -> Color(0xFF1DB954)
                            app.packageName.contains("whatsapp") -> Color(0xFF25D366)
                            app.packageName.contains("tinder") -> Color(0xFFFE3C72)
                            app.packageName.contains("twitch") -> Color(0xFF9146FF)
                            app.packageName.contains("discord") -> Color(0xFF5865F2)
                            else -> Color(app.packageName.hashCode()).copy(alpha = 1.0f)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(brandColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = app.appName.take(1).uppercase(),
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                    maxLines = 1
                )
                Text(
                    text = if (app.isInstalled) app.packageName else app.category,
                    fontSize = 11.sp,
                    color = TextHint,
                    maxLines = 1
                )
            }

            Checkbox(
                checked = app.isSelected,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = AccentCyan,
                    uncheckedColor = TextHint,
                    checkmarkColor = DarkBg
                )
            )
        }
    }
}