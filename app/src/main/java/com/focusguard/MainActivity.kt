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
import com.focusguard.database.AppDatabase

class MainActivity : AppCompatActivity() {

    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var settingsButton: Button
    private lateinit var database: AppDatabase
    private lateinit var accessibilityManager: AccessibilityManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize database
        database = AppDatabase.getDatabase(this)

        // Initialize accessibility manager
        accessibilityManager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager

        // Initialize UI components
        tabLayout = findViewById(R.id.tabLayout)
        viewPager = findViewById(R.id.viewPager)
        settingsButton = findViewById(R.id.settingsButton)

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

        // Check if accessibility service is enabled
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Please enable FocusGuard in Accessibility Settings", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Checks if the FocusGuard accessibility service is enabled
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val serviceName = "${packageName}/.service.BlockingAccessibilityService"
        return enabledServices.contains(serviceName)
    }

    /**
     * Opens the accessibility settings
     */
    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        // Check accessibility service status on resume
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "FocusGuard accessibility service is not enabled", Toast.LENGTH_SHORT).show()
        }
    }
}
