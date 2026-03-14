package com.focusguard.ui

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.focusguard.MainActivity
import com.focusguard.R
import com.focusguard.admin.DeviceOwnerManager

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
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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

    override fun onResume() {
        super.onResume()
        updateButtons()
    }

    private fun updateButtons() {
        val isA11yEnabled = isAccessibilityServiceEnabled()
        btnAccessibility.text = if (isA11yEnabled) "Concedido" else "Conceder"
        btnAccessibility.isEnabled = !isA11yEnabled

        val isAdminActive = deviceOwnerManager.isDeviceAdminActive() || deviceOwnerManager.isDeviceOwnerActive()
        btnDeviceAdmin.text = if (isAdminActive) "Concedido" else "Conceder"
        btnDeviceAdmin.isEnabled = !isAdminActive

        val isUsageAccessEnabled = isUsageAccessEnabled()
        btnUsageAccess.text = if (isUsageAccessEnabled) "Concedido" else "Conceder"
        btnUsageAccess.isEnabled = !isUsageAccessEnabled
    }

    private fun isUsageAccessEnabled(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val serviceName = "${packageName}/com.focusguard.service.BlockingAccessibilityService"
        return enabledServices.contains(serviceName)
    }
}
