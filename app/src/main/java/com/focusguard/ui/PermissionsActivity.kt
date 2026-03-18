package com.focusguard.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.focusguard.MainActivity
import com.focusguard.R
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.utils.PermissionUtils

class PermissionsActivity : AppCompatActivity() {

    private lateinit var btnAccessibility: Button
    private lateinit var btnUsageAccess: Button
    private lateinit var btnDeviceAdmin: Button
    private lateinit var btnSkip: Button
    private lateinit var deviceOwnerManager: DeviceOwnerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permissions)

        deviceOwnerManager = DeviceOwnerManager(this)

        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnUsageAccess = findViewById(R.id.btnUsageAccess)
        btnDeviceAdmin = findViewById(R.id.btnDeviceAdmin)
        btnSkip = findViewById(R.id.btnSkip)

        btnAccessibility.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+: orientar sobre permissões restritas
                showRestrictedPermissionGuide()
            } else {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        btnUsageAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        btnDeviceAdmin.setOnClickListener {
            if (!deviceOwnerManager.isDeviceAdminActive()) {
                deviceOwnerManager.requestDeviceAdmin()
            }
        }

        btnSkip.setOnClickListener {
            val prefs = getSharedPreferences("FocusGuardPrefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("hasSeenOnboarding", true).apply()

            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
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

    override fun onResume() {
        super.onResume()
        updateButtons()
    }

    private fun updateButtons() {
        val isA11yEnabled = PermissionUtils.isAccessibilityServiceEnabled(this)
        btnAccessibility.text = if (isA11yEnabled) "Concedido" else "Conceder"
        btnAccessibility.isEnabled = !isA11yEnabled

        val isAdminActive = deviceOwnerManager.isDeviceAdminActive() || deviceOwnerManager.isDeviceOwnerActive()
        btnDeviceAdmin.text = if (isAdminActive) "Concedido" else "Conceder"
        btnDeviceAdmin.isEnabled = !isAdminActive

        val isUsageAccessEnabled = PermissionUtils.isUsageAccessEnabled(this)
        btnUsageAccess.text = if (isUsageAccessEnabled) "Concedido" else "Conceder"
        btnUsageAccess.isEnabled = !isUsageAccessEnabled
    }
}
