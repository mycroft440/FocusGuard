package com.focusguard.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.focusguard.MainActivity
import com.focusguard.ui.compose.screens.PermissionsScreen
import com.focusguard.ui.compose.theme.FocusGuardTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class PermissionsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FocusGuardTheme {
                PermissionsScreen(onFinish = {
                    val prefs = getSharedPreferences("FocusGuardPrefs", Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("hasSeenOnboarding", true).apply()

                    // Reutiliza a MainActivity existente quando esta tela foi
                    // aberta pelo aviso de permissões e cria uma nova apenas no onboarding.
                    startActivity(
                        Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                    )
                    finish()
                })
            }
        }
    }
}
