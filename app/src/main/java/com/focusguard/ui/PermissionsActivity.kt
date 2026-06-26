package com.focusguard.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import androidx.activity.compose.setContent
import com.focusguard.ui.compose.screens.PermissionsScreen
import com.focusguard.ui.compose.theme.FocusGuardTheme

@AndroidEntryPoint
class PermissionsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FocusGuardTheme {
                PermissionsScreen(onFinish = {
                    val prefs = getSharedPreferences("FocusGuardPrefs", android.content.Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("hasSeenOnboarding", true).apply()
                    
                    startActivity(android.content.Intent(this, com.focusguard.MainActivity::class.java))
                    finish()
                })
            }
        }
    }
}

