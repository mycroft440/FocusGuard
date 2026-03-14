package com.focusguard

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.focusguard.admin.DeviceOwnerManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class MainActivity : AppCompatActivity() {

    private lateinit var btnPendingPermissions: MaterialButton
    private lateinit var cardTimeSession: MaterialCardView
    private lateinit var cardRecurringSession: MaterialCardView
    private lateinit var btnActiveSessions: Button
    private lateinit var deviceOwnerManager: DeviceOwnerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("FocusGuardPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("hasSeenOnboarding", false)) {
            startActivity(Intent(this, com.focusguard.ui.PermissionsActivity::class.java))
            finish()
            return
        }



        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        deviceOwnerManager = DeviceOwnerManager(this)

        btnPendingPermissions = findViewById(R.id.btnPendingPermissions)
        cardTimeSession = findViewById(R.id.cardTimeSession)
        cardRecurringSession = findViewById(R.id.cardRecurringSession)
        btnActiveSessions = findViewById(R.id.btnActiveSessions)

        // Botão vermelho redireciona para Permissions
        btnPendingPermissions.setOnClickListener {
            startActivity(Intent(this, com.focusguard.ui.PermissionsActivity::class.java))
        }

        // Time Session
        cardTimeSession.setOnClickListener {
            startActivity(Intent(this, com.focusguard.ui.TimeSessionActivity::class.java))
        }

        // Recurring Session
        cardRecurringSession.setOnClickListener {
            startActivity(Intent(this, com.focusguard.ui.RecurringSessionActivity::class.java))
        }

        // Active Sessions (For now we can open the old fragment, or a new Activity)
        btnActiveSessions.setOnClickListener {
            val fragment = com.focusguard.ui.BlockingSessionStatusFragment()
            fragment.show(supportFragmentManager, "BlockingSessionStatus")
        }


    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndBanner()
    }

    private fun checkPermissionsAndBanner() {
        val isA11yEnabled = isAccessibilityServiceEnabled()
        val isAdminActive = deviceOwnerManager.isDeviceAdminActive() || deviceOwnerManager.isDeviceOwnerActive()
        val isUsageAccessEnabled = isUsageAccessEnabled()

        if (!isA11yEnabled || !isAdminActive || !isUsageAccessEnabled) {
            btnPendingPermissions.visibility = View.VISIBLE
        } else {
            btnPendingPermissions.visibility = View.GONE
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val serviceName = "${packageName}/com.focusguard.service.BlockingAccessibilityService"
        return enabledServices.contains(serviceName)
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
}
