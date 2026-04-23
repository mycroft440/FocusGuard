package com.focusguard.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.focusguard.MainActivity
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.ui.compose.screens.PermissionState
import com.focusguard.ui.compose.screens.PermissionsScreen
import com.focusguard.ui.compose.theme.FocusGuardTheme
import com.focusguard.utils.PermissionUtils

class PermissionsActivity : ComponentActivity() {

    private lateinit var deviceOwnerManager: DeviceOwnerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        deviceOwnerManager = DeviceOwnerManager(this)

        setContent {
            FocusGuardTheme {
                val activity = this@PermissionsActivity

                var permState by remember { mutableStateOf(PermissionState()) }
                var resumeKey by remember { mutableIntStateOf(0) }

                LaunchedEffect(resumeKey) {
                    permState = PermissionState(
                        accessibility = PermissionUtils.isAccessibilityServiceEnabled(activity),
                        usageAccess = PermissionUtils.isUsageAccessEnabled(activity),
                        deviceAdmin = deviceOwnerManager.isDeviceAdminActive() || deviceOwnerManager.isDeviceOwnerActive(),
                        batteryOptimization = PermissionUtils.isBatteryOptimizationIgnored(activity)
                    )
                }

                DisposableEffect(Unit) {
                    val callback = object : DefaultLifecycleObserver {
                        override fun onResume(owner: LifecycleOwner) {
                            resumeKey++
                        }
                    }
                    activity.lifecycle.addObserver(callback)
                    onDispose { activity.lifecycle.removeObserver(callback) }
                }

                PermissionsScreen(
                    permissionState = permState,
                    onAccessibilityClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            showRestrictedPermissionGuide()
                        } else {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    },
                    onUsageAccessClick = {
                        try {
                            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                                data = Uri.parse("package:$packageName")
                            }
                            startActivity(intent)
                        } catch (e: Exception) {
                            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        }
                    },
                    onDeviceAdminClick = {
                        if (!deviceOwnerManager.isDeviceAdminActive()) {
                            deviceOwnerManager.requestDeviceAdmin()
                        }
                    },
                    onBatteryClick = {
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:$packageName")
                            }
                            startActivity(intent)
                        } catch (e: Exception) {
                            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    },
                    onSkipClick = {
                        val prefs = getSharedPreferences("FocusGuardPrefs", Context.MODE_PRIVATE)
                        prefs.edit().putBoolean("hasSeenOnboarding", true).apply()
                        startActivity(Intent(activity, MainActivity::class.java))
                        finish()
                    }
                )
            }
        }
    }

    private fun showRestrictedPermissionGuide() {
        AlertDialog.Builder(this)
            .setTitle("Permissão Restrita (Android 13+)")
            .setMessage(
                "O Android 13+ restringe a acessibilidade para apps instalados fora da Play Store.\n\n" +
                "Siga estes passos:\n\n" +
                "1. Toque em \"Abrir Config. do App\" abaixo\n" +
                "2. Toque nos 3 pontos (⋮) no canto superior direito\n" +
                "3. Selecione \"Permitir configurações restritas\"\n" +
                "4. Volte aqui e toque novamente em \"Conceder\" para abrir as configurações de acessibilidade"
            )
            .setPositiveButton("Abrir Config. do App") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
            .setNeutralButton("Ir para Acessibilidade") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
