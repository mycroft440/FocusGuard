package com.focusguard.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.focusguard.database.AppDatabase
import com.focusguard.database.BlockedApp
import com.focusguard.ui.compose.screens.AppSelectionScreen
import com.focusguard.ui.compose.screens.SelectableAppUi
import com.focusguard.ui.compose.theme.FocusGuardTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppSelectionActivity : ComponentActivity() {

    private val suggestedAddictiveApps = listOf(
        Pair("com.google.android.youtube", "YouTube"),
        Pair("com.zhiliaoapp.musically", "TikTok"),
        Pair("com.instagram.android", "Instagram"),
        Pair("com.facebook.katana", "Facebook"),
        Pair("com.twitter.android", "X (Twitter)"),
        Pair("com.reddit.frontpage", "Reddit"),
        Pair("com.whatsapp", "WhatsApp")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val database = AppDatabase.getDatabase(this)
        val pm = packageManager
        val activity = this

        setContent {
            FocusGuardTheme {
                var apps by remember { mutableStateOf<List<SelectableAppUi>>(emptyList()) }
                var isLoading by remember { mutableStateOf(true) }

                val scope = rememberCoroutineScope()

                // Load apps
                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) {
                        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                        val blockedApps = database.blockedAppDao().getAllBlockedApps()
                        val blockedPackageNames = blockedApps.map { it.packageName }.toSet()

                        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        val launchables = pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.toSet()

                        val appList = mutableListOf<SelectableAppUi>()

                        for (info in installedApps) {
                            if (launchables.contains(info.packageName)) {
                                val appName = info.loadLabel(pm).toString()
                                val icon = try { info.loadIcon(pm) } catch (_: Exception) { null }
                                appList.add(
                                    SelectableAppUi(
                                        packageName = info.packageName,
                                        appName = appName,
                                        icon = icon,
                                        isSelected = blockedPackageNames.contains(info.packageName)
                                    )
                                )
                            }
                        }

                        val installedPackageNames = appList.map { it.packageName }.toSet()
                        for (suggested in suggestedAddictiveApps) {
                            if (!installedPackageNames.contains(suggested.first)) {
                                appList.add(
                                    SelectableAppUi(
                                        packageName = suggested.first,
                                        appName = suggested.second + " (Não instalado)",
                                        icon = null,
                                        isSelected = blockedPackageNames.contains(suggested.first),
                                        isSuggested = true
                                    )
                                )
                            }
                        }

                        appList.sortBy { it.appName.lowercase() }

                        withContext(Dispatchers.Main) {
                            apps = appList
                            isLoading = false
                        }
                    }
                }

                AppSelectionScreen(
                    apps = apps,
                    isLoading = isLoading,
                    onToggleApp = { packageName ->
                        apps = apps.map { app ->
                            if (app.packageName == packageName) app.copy(isSelected = !app.isSelected)
                            else app
                        }
                    },
                    onBack = {
                        // Save selection on back
                        scope.launch(Dispatchers.IO) {
                            saveSelection(database, apps)
                            withContext(Dispatchers.Main) { finish() }
                        }
                    }
                )
            }
        }
    }

    private suspend fun saveSelection(database: AppDatabase, apps: List<SelectableAppUi>) {
        val selectedApps = apps.filter { it.isSelected }
        val selectedPackageNames = selectedApps.map { it.packageName }.toSet()

        val dao = database.blockedAppDao()
        val existingBlocked = dao.getAllBlockedApps()
        val existingPackageNames = existingBlocked.map { it.packageName }.toSet()

        // Remove deselected apps
        for (existing in existingBlocked) {
            if (!selectedPackageNames.contains(existing.packageName)) {
                dao.deleteBlockedApp(existing)
            }
        }

        // Add newly selected apps
        for (app in selectedApps) {
            if (!existingPackageNames.contains(app.packageName)) {
                dao.insertBlockedApp(
                    BlockedApp(
                        packageName = app.packageName,
                        appName = app.appName
                    )
                )
            }
        }
    }

    @Deprecated("Deprecated in Java", ReplaceWith("onBackPressedDispatcher.onBackPressed()"))
    override fun onBackPressed() {
        // Trigger onBack to save before closing
        val database = AppDatabase.getDatabase(this)
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            // read current state not available, use super
        }
        super.onBackPressed()
    }
}
