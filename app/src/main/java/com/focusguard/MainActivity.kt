package com.focusguard

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.utils.PermissionUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class MainActivity : AppCompatActivity() {

    private lateinit var btnPendingPermissions: MaterialButton
    private lateinit var cardTimeSession: MaterialCardView
    private lateinit var cardRecurringSession: MaterialCardView
    private lateinit var btnActiveSessions: MaterialButton
    private lateinit var btnDeviceOwnerTutorial: MaterialButton
    private lateinit var deviceOwnerManager: DeviceOwnerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("FocusGuardPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("hasSeenOnboarding", false)) {
            startActivity(Intent(this, com.focusguard.ui.PermissionsActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        deviceOwnerManager = DeviceOwnerManager(this)

        btnPendingPermissions = findViewById(R.id.btnPendingPermissions)
        cardTimeSession = findViewById(R.id.cardTimeSession)
        cardRecurringSession = findViewById(R.id.cardRecurringSession)
        btnActiveSessions = findViewById(R.id.btnActiveSessions)
        btnDeviceOwnerTutorial = findViewById(R.id.btnDeviceOwnerTutorial)

        btnPendingPermissions.setOnClickListener {
            startActivity(Intent(this, com.focusguard.ui.PermissionsActivity::class.java))
        }

        cardTimeSession.setOnClickListener {
            startActivity(Intent(this, com.focusguard.ui.TimeSessionActivity::class.java))
        }

        cardRecurringSession.setOnClickListener {
            startActivity(Intent(this, com.focusguard.ui.RecurringSessionActivity::class.java))
        }

        btnActiveSessions.setOnClickListener {
            val fragment = com.focusguard.ui.BlockingSessionStatusFragment()
            fragment.show(supportFragmentManager, "BlockingSessionStatus")
        }

        btnDeviceOwnerTutorial.setOnClickListener {
            deviceOwnerManager.setAsDeviceOwner()
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndBanner()
    }

    private fun checkPermissionsAndBanner() {
        val isA11yEnabled = PermissionUtils.isAccessibilityServiceEnabled(this)
        val isAdminActive = deviceOwnerManager.isDeviceAdminActive() || deviceOwnerManager.isDeviceOwnerActive()
        val isUsageAccessEnabled = PermissionUtils.isUsageAccessEnabled(this)

        if (!isA11yEnabled || !isAdminActive || !isUsageAccessEnabled) {
            btnPendingPermissions.visibility = View.VISIBLE
        } else {
            btnPendingPermissions.visibility = View.GONE
        }
    }
}
