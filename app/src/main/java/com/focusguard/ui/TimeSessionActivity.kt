package com.focusguard.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.focusguard.database.AppDatabase
import com.focusguard.database.BlockedApp
import com.focusguard.database.BlockedWebsite
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.ui.compose.screens.TimeSessionScreen
import com.focusguard.ui.compose.theme.FocusGuardTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TimeSessionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // VERIFICAÇÃO DE SEGURANÇA
        val sessionCheckManager = BlockingSessionManager.getInstance(this)
        kotlinx.coroutines.runBlocking {
            if (sessionCheckManager.hasRegisteredSession()) {
                Toast.makeText(this@TimeSessionActivity, "Acesso negado: Há uma sessão ativa.", Toast.LENGTH_LONG).show()
                finish()
                return@runBlocking
            }
        }

        val sessionManager = BlockingSessionManager.getInstance(this)
        val database = AppDatabase.getDatabase(this)
        val pm = packageManager
        val activity = this

        setContent {
            FocusGuardTheme {
                var appsCount by remember { mutableIntStateOf(0) }
                var sitesCount by remember { mutableIntStateOf(0) }
                var selectedApps by remember { mutableStateOf<List<Pair<BlockedApp, android.graphics.drawable.Drawable?>>>(emptyList()) }
                var selectedSites by remember { mutableStateOf<List<BlockedWebsite>>(emptyList()) }
                var resumeKey by remember { mutableIntStateOf(0) }

                val scope = rememberCoroutineScope()

                // Clear previous selections on first load
                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) {
                        database.blockedAppDao().deleteAllBlockedApps()
                        database.blockedWebsiteDao().deleteAllBlockedWebsites()
                    }
                }

                // Refresh counts on resume
                LaunchedEffect(resumeKey) {
                    withContext(Dispatchers.IO) {
                        val apps = database.blockedAppDao().getAllBlockedApps()
                        val sites = database.blockedWebsiteDao().getAllBlockedWebsites()
                        val appsWithIcons = apps.map { app ->
                            val icon = try { pm.getApplicationIcon(app.packageName) } catch (_: Exception) { null }
                            Pair(app, icon)
                        }
                        withContext(Dispatchers.Main) {
                            appsCount = apps.size
                            sitesCount = sites.size
                            selectedApps = appsWithIcons
                            selectedSites = sites
                        }
                    }
                }

                DisposableEffect(Unit) {
                    val callback = object : DefaultLifecycleObserver {
                        override fun onResume(owner: LifecycleOwner) { resumeKey++ }
                    }
                    activity.lifecycle.addObserver(callback)
                    onDispose { activity.lifecycle.removeObserver(callback) }
                }

                TimeSessionScreen(
                    appsCount = appsCount,
                    sitesCount = sitesCount,
                    selectedApps = selectedApps,
                    selectedSites = selectedSites,
                    onSelectApps = {
                        startActivity(Intent(activity, AppSelectionActivity::class.java))
                    },
                    onSelectSites = {
                        startActivity(Intent(activity, WebsiteSelectionActivity::class.java))
                    },
                    onStartSession = { days, hours ->
                        if (days < 0 || hours < 0) {
                            Toast.makeText(activity, "Valores não podem ser negativos", Toast.LENGTH_SHORT).show()
                            return@TimeSessionScreen
                        }
                        if (days == 0 && hours == 0) {
                            Toast.makeText(activity, "Defina pelo menos 1 hora ou dia", Toast.LENGTH_SHORT).show()
                            return@TimeSessionScreen
                        }
                        if (days > 90) {
                            Toast.makeText(activity, "O bloqueio máximo permitido é de 90 dias", Toast.LENGTH_SHORT).show()
                            return@TimeSessionScreen
                        }
                        scope.launch(Dispatchers.IO) {
                            val ac = database.blockedAppDao().getAllBlockedApps().size
                            val sc = database.blockedWebsiteDao().getAllBlockedWebsites().size
                            sessionManager.startTimerSession(days, hours, ac, sc)
                            withContext(Dispatchers.Main) { finish() }
                        }
                    },
                    onBack = { finish() }
                )
            }
        }
    }
}
