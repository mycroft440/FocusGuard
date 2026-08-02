package com.focusguard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.focusguard.manager.PomodoroManager
import com.focusguard.security.AuthManager
import com.focusguard.ui.PermissionsActivity
import com.focusguard.ui.compose.navigation.FocusGuardNavHost
import com.focusguard.ui.compose.theme.FocusGuardTheme
import com.focusguard.utils.FocusGuardLogger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    // Injetados via Hilt — usam singletons do app (corrigido em P0-2 / P3-1).
    // Antes eram instanciados direto, criando instâncias paralelas e disparando
    // migrações em paralelo (AuthManager) ou multiplas instâncias de manager.
    @Inject lateinit var authManager: AuthManager

    private lateinit var pomodoroManager: PomodoroManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FocusGuardLogger.init(this)
        FocusGuardLogger.log("MainActivity", "onCreate disparado")

        val prefs = getSharedPreferences("FocusGuardPrefs", Context.MODE_PRIVATE)
        val attemptCount = prefs.getInt("launchAttemptCount", 0) + 1
        prefs.edit().putInt("launchAttemptCount", attemptCount).apply()

        if (!prefs.getBoolean("hasSeenOnboarding", false)) {
            startActivity(Intent(this, PermissionsActivity::class.java))
            finish()
            return
        }

        // PomodoroManager ainda usa o singleton legado.
        pomodoroManager = PomodoroManager.getInstance(applicationContext)

        FocusGuardLogger.log("MainActivity", "Managers inicializados com sucesso")

        setContent {
            FocusGuardTheme {
                FocusGuardNavHost(
                    activity = this,
                    authManager = authManager,
                    pomodoroManager = pomodoroManager
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        FocusGuardLogger.log("MainActivity", "onResume disparado")
    }

    override fun onPause() {
        super.onPause()
        FocusGuardLogger.log("MainActivity", "onPause disparado")
    }
}
