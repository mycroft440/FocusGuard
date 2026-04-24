package com.focusguard.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
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

class PermissionsActivity : AppCompatActivity() {

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
                        handleAccessibilityPermission()
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

    private fun handleAccessibilityPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: First try to allow restricted settings, then open accessibility
            AlertDialog.Builder(this)
                .setTitle("Ativar Acessibilidade")
                .setMessage(
                    "Para funcionar corretamente, o FocusGuard precisa da permissão de Acessibilidade.\n\n" +
                    "No Android 13+, apps instalados fora da Play Store podem precisar de uma etapa extra:\n\n" +
                    "1. Primeiro toque em \"Liberar Restrição\" para abrir as configurações do app\n" +
                    "2. Procure a opção \"Permitir configurações restritas\" (pode estar no menu ⋮ ou na própria tela)\n" +
                    "3. Depois toque em \"Ativar Acessibilidade\" para encontrar o FocusGuard na lista\n\n" +
                    "Se não encontrar a opção de restrição, vá direto para Acessibilidade."
                )
                .setPositiveButton("Ativar Acessibilidade") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNeutralButton("Liberar Restrição") { _, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    } catch (_: Exception) {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        } else {
            // Android 12 and below: Go directly to accessibility settings
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }
}
