package com.focusguard

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.focusguard.adapter.TabAdapter
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.database.AppDatabase
import com.focusguard.ui.StartBlockingSessionFragment
import com.focusguard.ui.BlockingSessionStatusFragment

class MainActivity : AppCompatActivity() {

    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var settingsButton: Button
    private lateinit var deviceOwnerButton: Button
    private lateinit var startBlockingButton: Button
    private lateinit var blockingStatusButton: Button
    private lateinit var themeButton: Button
    private lateinit var database: AppDatabase
    private lateinit var accessibilityManager: AccessibilityManager
    private lateinit var deviceOwnerManager: DeviceOwnerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        val sharedPrefs = getSharedPreferences("ThemePrefs", Context.MODE_PRIVATE)
        val isGrayTheme = sharedPrefs.getBoolean("isGrayTheme", false)
        if (isGrayTheme) {
            setTheme(R.style.Theme_FocusGuard_Gray)
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize database
        database = AppDatabase.getDatabase(this)

        // Initialize accessibility manager
        accessibilityManager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager

        // Initialize Device Owner Manager
        deviceOwnerManager = DeviceOwnerManager(this)

        // Initialize UI components
        tabLayout = findViewById(R.id.tabLayout)
        viewPager = findViewById(R.id.viewPager)
        settingsButton = findViewById(R.id.settingsButton)
        deviceOwnerButton = findViewById(R.id.deviceOwnerButton)
        startBlockingButton = findViewById(R.id.startBlockingButton)
        blockingStatusButton = findViewById(R.id.blockingStatusButton)
        themeButton = findViewById(R.id.themeButton)

        // Setup ViewPager2 with adapter
        val adapter = TabAdapter(this)
        viewPager.adapter = adapter

        // Connect TabLayout with ViewPager2
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Blocked Apps"
                1 -> "Blocked Websites"
                else -> "Tab $position"
            }
        }.attach()

        // Settings button click listener
        settingsButton.setOnClickListener {
            openAccessibilitySettings()
        }

        // Device Owner button click listener
        deviceOwnerButton.setOnClickListener {
            openDeviceOwnerSettings()
        }

        // Start Blocking button click listener
        startBlockingButton.setOnClickListener {
            openStartBlockingSession()
        }

        // Blocking Status button click listener
        blockingStatusButton.setOnClickListener {
            openBlockingSessionStatus()
        }

        // Theme button click listener
        themeButton.setOnClickListener {
            val prefs = getSharedPreferences("ThemePrefs", Context.MODE_PRIVATE)
            val isGray = prefs.getBoolean("isGrayTheme", false)
            prefs.edit().putBoolean("isGrayTheme", !isGray).apply()
            recreate()
        }

        // Check if accessibility service is enabled
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Please enable FocusGuard in Accessibility Settings", Toast.LENGTH_LONG).show()
        }

        // Update Device Owner button text based on status
        updateDeviceOwnerButtonStatus()
    }

    /**
     * Checks if the FocusGuard accessibility service is enabled
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val serviceName = "${packageName}/com.focusguard.service.BlockingAccessibilityService"
        return enabledServices.contains(serviceName)
    }

    /**
     * Opens the accessibility settings
     */
    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    /**
     * Opens Device Owner settings/instructions
     */
    private fun openDeviceOwnerSettings() {
        if (deviceOwnerManager.isDeviceOwnerActive()) {
            Toast.makeText(this, "Device Owner Mode is Active", Toast.LENGTH_SHORT).show()
        } else if (deviceOwnerManager.isDeviceAdminActive()) {
            Toast.makeText(this, "Device Admin is Active. Use ADB to set as Device Owner.", Toast.LENGTH_LONG).show()
            deviceOwnerManager.setAsDeviceOwner()
        } else {
            // Request device admin activation
            deviceOwnerManager.requestDeviceAdmin()
        }
    }

    /**
     * Update Device Owner button status
     */
    private fun updateDeviceOwnerButtonStatus() {
        val statusText = when {
            deviceOwnerManager.isDeviceOwnerActive() -> "Device Owner: Active"
            deviceOwnerManager.isDeviceAdminActive() -> "Device Admin: Active"
            else -> "Enable Device Owner Mode"
        }
        deviceOwnerButton.text = statusText
    }

    /**
     * Opens the start blocking session fragment
     */
    private fun openStartBlockingSession() {
        val fragment = StartBlockingSessionFragment()
        fragment.show(supportFragmentManager, "StartBlockingSession")
    }

    /**
     * Opens the blocking session status fragment
     */
    private fun openBlockingSessionStatus() {
        val fragment = BlockingSessionStatusFragment()
        fragment.show(supportFragmentManager, "BlockingSessionStatus")
    }

    override fun onResume() {
        super.onResume()
        // Check accessibility service status on resume
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "FocusGuard accessibility service is not enabled", Toast.LENGTH_SHORT).show()
        }
        // Update Device Owner button status
        updateDeviceOwnerButtonStatus()
    }
}
