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
import com.focusguard.ui.compose.screens.RecurringSessionScreen
import com.focusguard.ui.compose.theme.FocusGuardTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecurringSessionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // VERIFICAÇÃO DE SEGURANÇA
        val sessionCheckManager = BlockingSessionManager.getInstance(this)
        kotlinx.coroutines.runBlocking {
            if (sessionCheckManager.hasRegisteredSession()) {
                Toast.makeText(this@RecurringSessionActivity, "Acesso negado: Há uma sessão ativa.", Toast.LENGTH_LONG).show()
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

                RecurringSessionScreen(
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
                    onStartSession = { startH, startM, endH, endM, days, months ->
                        if (startH == -1 || endH == -1) {
                            Toast.makeText(activity, "Defina os horários de início e fim", Toast.LENGTH_SHORT).show()
                            return@RecurringSessionScreen
                        }
                        if (startH == endH && startM == endM) {
                            Toast.makeText(activity, "Horário de início e fim não podem ser iguais", Toast.LENGTH_SHORT).show()
                            return@RecurringSessionScreen
                        }
                        if (months <= 0 || months > 36) {
                            Toast.makeText(activity, "Duração deve ser definida entre 1 e 36 meses", Toast.LENGTH_SHORT).show()
                            return@RecurringSessionScreen
                        }
                        if (days.isEmpty()) {
                            Toast.makeText(activity, "Selecione pelo menos um dia da semana", Toast.LENGTH_SHORT).show()
                            return@RecurringSessionScreen
                        }
                        scope.launch(Dispatchers.IO) {
                            val ac = database.blockedAppDao().getAllBlockedApps().size
                            val sc = database.blockedWebsiteDao().getAllBlockedWebsites().size
                            sessionManager.startRecurringSession(startH, startM, endH, endM, days, months, ac, sc)
                            withContext(Dispatchers.Main) { finish() }
                        }
                    },
                    onBack = { finish() }
                )
            }
        }
    }
}
