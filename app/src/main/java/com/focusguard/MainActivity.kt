package com.focusguard

import android.content.Context
import android.content.Intent
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.focusmode.FocusModeKioskController
import com.focusguard.focusmode.FocusModeManager
import com.focusguard.manager.PomodoroManager
import com.focusguard.security.AuthManager
import com.focusguard.ui.PermissionsActivity
import com.focusguard.ui.compose.navigation.FocusGuardNavHost
import com.focusguard.ui.compose.theme.FocusGuardTheme
import com.focusguard.utils.FocusGuardLogger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    // Injetados via Hilt — usam singletons do app (corrigido em P0-2 / P3-1).
    // Antes eram instanciados direto, criando instâncias paralelas e disparando
    // migrações em paralelo (AuthManager) ou multiplas instâncias de manager.
    @Inject lateinit var authManager: AuthManager
    @Inject lateinit var focusModeManager: FocusModeManager

    private lateinit var pomodoroManager: PomodoroManager
    private var grayscaleApplied: Boolean? = null

    /**
     * Last-resort Back guard for every FocusGuard screen. Compose screens keep
     * their own navigation handlers, but if one of them stops consuming Back,
     * an active Focus Mode must never finish the root Activity and reveal Home.
     */
    private val focusModeBackGuard = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (!focusModeManager.isActive()) {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
                return
            }

            FocusGuardLogger.log("FocusMode", "Voltar interceptado pelo shell do Modo Foco")
            FocusModeKioskController.reconcileSystemRestrictions(this@MainActivity)
            enforceFocusModeLockTask()
        }
    }

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
        onBackPressedDispatcher.addCallback(this, focusModeBackGuard)
        updateFocusModeBackGuard()
        FocusModeKioskController.reconcileSystemRestrictions(this)

        FocusGuardLogger.log("MainActivity", "Managers inicializados com sucesso")

        setContent {
            FocusGuardTheme {
                FocusGuardNavHost(
                    activity = this,
                    authManager = authManager,
                    pomodoroManager = pomodoroManager,
                    focusModeManager = focusModeManager,
                    onEnforceFocusModeLockTask = ::enforceFocusModeLockTask
                )
            }
        }
        applyFocusModeGrayscale(
            focusModeManager.session.value?.let {
                it.isActive() && it.grayscaleEnabled
            } == true
        )
        observeFocusModeVisualState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateFocusModeBackGuard()
        enforceFocusModeLockTask()
    }

    override fun onResume() {
        super.onResume()
        FocusGuardLogger.log("MainActivity", "onResume disparado")
        updateFocusModeBackGuard()
        FocusModeKioskController.reconcileSystemRestrictions(this)
        enforceFocusModeLockTask()
    }

    override fun onPause() {
        super.onPause()
        FocusGuardLogger.log("MainActivity", "onPause disparado")
    }

    fun enforceFocusModeLockTask() {
        updateFocusModeBackGuard()
        FocusModeKioskController.reconcileSystemRestrictions(this)
        val deviceOwnerManager = DeviceOwnerManager.getInstance(applicationContext)
        // Lock Task allowlisting survives process death. Re-enter it immediately
        // on resume, before the asynchronous full policy reconciliation, so there
        // is no Home-button escape window after Android recreates this activity.
        if (focusModeManager.isActive() &&
            deviceOwnerManager.isFocusModeLockTaskPermitted()
        ) {
            runCatching { startLockTask() }
        }
        lifecycleScope.launch {
            if (!focusModeManager.ensureEnforced()) {
                updateFocusModeBackGuard()
                FocusModeKioskController.reconcileSystemRestrictions(this@MainActivity)
                return@launch
            }
            updateFocusModeBackGuard()
            FocusModeKioskController.reconcileSystemRestrictions(this@MainActivity)
            if (!deviceOwnerManager.isDeviceOwnerActive()) return@launch
            runCatching { startLockTask() }
                .onFailure { error ->
                    FocusGuardLogger.logError(
                        "FocusMode",
                        "Falha ao iniciar Lock Task na atividade principal",
                        error
                    )
                }
        }
    }

    private fun updateFocusModeBackGuard() {
        focusModeBackGuard.isEnabled = focusModeManager.isActive()
    }

    private fun observeFocusModeVisualState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                focusModeManager.session.collect { session ->
                    val active = session?.isActive() == true
                    focusModeBackGuard.isEnabled = active
                    FocusModeKioskController.reconcileSystemRestrictions(this@MainActivity)
                    applyFocusModeGrayscale(active && session?.grayscaleEnabled == true)
                    if (active) {
                        val deviceOwnerManager = DeviceOwnerManager.getInstance(applicationContext)
                        if (deviceOwnerManager.isFocusModeLockTaskPermitted()) {
                            runCatching { startLockTask() }
                        }
                    }
                }
            }
        }
    }

    /**
     * Android exposes no public API for a third-party DPC to recolor every app.
     * Focus Mode therefore desaturates the complete FocusGuard activity layer;
     * selected external emergency/communication apps keep their own rendering.
     */
    private fun applyFocusModeGrayscale(enabled: Boolean) {
        if (grayscaleApplied == enabled) return
        grayscaleApplied = enabled
        val decorView = window.decorView
        if (enabled) {
            val matrix = ColorMatrix().apply { setSaturation(0f) }
            val paint = Paint().apply {
                colorFilter = ColorMatrixColorFilter(matrix)
            }
            decorView.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        } else {
            decorView.setLayerType(View.LAYER_TYPE_NONE, null)
        }
        decorView.invalidate()
    }
}
