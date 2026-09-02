package com.focusguard.ui.compose.screens

import kotlin.OptIn
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.ui.compose.components.FocusGuardAppIcon
import com.focusguard.ui.compose.theme.*

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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_selection_title), color = TextPrimary) },
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
        AppSelectionList(
            apps = apps,
            isLoading = isLoading,
            onToggleApp = onToggleApp,
            modifier = Modifier.padding(padding)
        )
    }
}

/**
 * The searchable app list on its own, without a top bar.
 *
 * Selected apps are intentionally separated from the available-app catalogue.
 * This makes the future block explicit: selecting an app moves it into the
 * selected list, and tapping it there removes it from the block again.
 */
@Composable
fun AppSelectionList(
    apps: List<SelectableAppUi>,
    isLoading: Boolean,
    onToggleApp: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var expandUninstalled by remember { mutableStateOf(false) }
    var expandInstalled by remember { mutableStateOf(false) }

    val selectedApps = remember(apps) {
        apps.filter { it.isSelected }.sortedBy { it.appName.lowercase() }
    }
    val availableApps = remember(apps, searchQuery) {
        apps
            .asSequence()
            .filterNot { it.isSelected }
            .filter {
                searchQuery.isBlank() ||
                    it.appName.contains(searchQuery, ignoreCase = true) ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
            }
            .toList()
    }
    val installedApps = remember(availableApps) { availableApps.filter { it.isInstalled } }
    val uninstalledApps = remember(availableApps) { availableApps.filter { !it.isInstalled } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBg)
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = {
                Text(stringResource(R.string.limits_search_placeholder), color = TextHint)
            },
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
                if (selectedApps.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.app_selection_selected),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentCyan,
                            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp, start = 4.dp)
                        )
                        Text(
                            text = stringResource(R.string.app_selection_selected_hint),
                            fontSize = 12.sp,
                            color = TextHint,
                            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                        )
                    }
                    items(selectedApps, key = { "selected_${it.packageName}" }) { app ->
                        AppSelectionItem(
                            app = app,
                            onToggle = { onToggleApp(app.packageName) }
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = CardBorder, thickness = 1.dp)
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                if (uninstalledApps.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.app_selection_preventive),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentCyan,
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                        )
                    }

                    val visibleUninstalled = if (expandUninstalled || searchQuery.isNotBlank()) {
                        uninstalledApps
                    } else {
                        uninstalledApps.take(3)
                    }

                    items(visibleUninstalled, key = { "available_${it.packageName}" }) { app ->
                        AppSelectionItem(
                            app = app,
                            onToggle = { onToggleApp(app.packageName) }
                        )
                    }

                    if (!expandUninstalled && searchQuery.isBlank() && uninstalledApps.size > 3) {
                        item {
                            TextButton(
                                onClick = { expandUninstalled = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    stringResource(
                                        R.string.app_selection_show_more,
                                        uninstalledApps.size - 3
                                    ),
                                    color = AccentCyan
                                )
                            }
                        }
                    }

                    if (expandUninstalled && searchQuery.isBlank()) {
                        item {
                            TextButton(
                                onClick = { expandUninstalled = false },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    stringResource(R.string.app_selection_show_less),
                                    color = AccentCyan
                                )
                            }
                        }
                    }

                    if (installedApps.isNotEmpty()) {
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
                    }
                } else if (installedApps.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.app_selection_installed),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                        )
                    }
                }

                if (installedApps.isNotEmpty()) {
                    val visibleInstalled = if (expandInstalled || searchQuery.isNotBlank()) {
                        installedApps
                    } else {
                        installedApps.take(3)
                    }

                    items(visibleInstalled, key = { "available_${it.packageName}" }) { app ->
                        AppSelectionItem(
                            app = app,
                            onToggle = { onToggleApp(app.packageName) }
                        )
                    }

                    if (!expandInstalled && searchQuery.isBlank() && installedApps.size > 3) {
                        item {
                            TextButton(
                                onClick = { expandInstalled = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    stringResource(
                                        R.string.app_selection_show_more,
                                        installedApps.size - 3
                                    ),
                                    color = TextPrimary.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }

                    if (expandInstalled && searchQuery.isBlank()) {
                        item {
                            TextButton(
                                onClick = { expandInstalled = false },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    stringResource(R.string.app_selection_show_less),
                                    color = TextPrimary.copy(alpha = 0.7f)
                                )
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
        border = BorderStroke(
            1.dp,
            if (app.isSelected) AccentCyan.copy(alpha = 0.3f) else CardBorder
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FocusGuardAppIcon(
                packageName = app.packageName,
                appName = app.appName,
                iconUrl = app.iconUrl,
                modifier = Modifier.size(40.dp),
                cornerRadius = 10.dp
            )

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
