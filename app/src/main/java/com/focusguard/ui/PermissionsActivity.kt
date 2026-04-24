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
            val isRestricted = isAccessibilityServiceRestricted()

            if (isRestricted) {
                AlertDialog.Builder(this)
                    .setTitle("Ativar Acessibilidade")
                    .setMessage(
                        "O FocusGuard precisa da permissão de Acessibilidade, mas o Android detectou uma restrição.\n\n" +
                        "Siga estes passos:\n\n" +
                        "1. Toque em \"Liberar Restrição\" abaixo\n" +
                        "2. Procure a opção \"Permitir configurações restritas\"\n" +
                        "3. Volte e toque em \"Ativar Acessibilidade\""
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
                // No restriction — go directly to accessibility settings
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        } else {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun isAccessibilityServiceRestricted(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val appOps = getSystemService(android.app.AppOpsManager::class.java)
                val mode = appOps.noteOpNoThrow(
                    "android:access_restricted_settings",
                    android.os.Process.myUid(),
                    packageName
                )
                mode != android.app.AppOpsManager.MODE_ALLOWED
            } else {
                false
            }
        } catch (_: Exception) {
            // If check fails, assume restricted on Android 13+ sideloaded apps
            true
        }
    }
}
