package com.focusguard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.focusguard.admin.DeviceOwnerManager
import com.google.android.material.card.MaterialCardView

class MainActivity : AppCompatActivity() {

    private lateinit var permissionsBanner: LinearLayout
    private lateinit var cardTimeSession: MaterialCardView
    private lateinit var cardRecurringSession: MaterialCardView
    private lateinit var btnActiveSessions: Button
    private lateinit var btnTheme: Button
    private lateinit var deviceOwnerManager: DeviceOwnerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("FocusGuardPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("hasSeenOnboarding", false)) {
            startActivity(Intent(this, com.focusguard.ui.PermissionsActivity::class.java))
            finish()
            return
        }

        val sharedPrefs = getSharedPreferences("ThemePrefs", Context.MODE_PRIVATE)
        val isGrayTheme = sharedPrefs.getBoolean("isGrayTheme", false)
        if (isGrayTheme) {
            setTheme(R.style.Theme_FocusGuard_Gray)
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        deviceOwnerManager = DeviceOwnerManager(this)

        permissionsBanner = findViewById(R.id.permissionsBanner)
        cardTimeSession = findViewById(R.id.cardTimeSession)
        cardRecurringSession = findViewById(R.id.cardRecurringSession)
        btnActiveSessions = findViewById(R.id.btnActiveSessions)
        btnTheme = findViewById(R.id.btnTheme)

        // Banner click redirects to Permissions
        permissionsBanner.setOnClickListener {
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

        // Theme Toggle
        btnTheme.setOnClickListener {
            val themePrefs = getSharedPreferences("ThemePrefs", Context.MODE_PRIVATE)
            val isGray = themePrefs.getBoolean("isGrayTheme", false)
            
            val attempts = themePrefs.getInt("themeChangeAttempts", 0) + 1
            themePrefs.edit().putInt("themeChangeAttempts", attempts).apply()
            
            if (attempts <= 5) {
                println("Tentativa $attempts de alternância do tema. Tema atual: ${if (isGray) "Cinza" else "Padrão"}. Alterando para: ${if (!isGray) "Cinza" else "Padrão"}.")
            }

            themePrefs.edit().putBoolean("isGrayTheme", !isGray).apply()
            recreate()
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndBanner()
    }

    private fun checkPermissionsAndBanner() {
        val isA11yEnabled = isAccessibilityServiceEnabled()
        val isAdminActive = deviceOwnerManager.isDeviceAdminActive() || deviceOwnerManager.isDeviceOwnerActive()

        if (!isA11yEnabled || !isAdminActive) {
            permissionsBanner.visibility = View.VISIBLE
        } else {
            permissionsBanner.visibility = View.GONE
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
}
