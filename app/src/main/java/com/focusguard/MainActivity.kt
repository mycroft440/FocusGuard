package com.focusguard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.focusguard.ui.MainPagerAdapter
import com.focusguard.ui.PermissionsActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("FocusGuardPrefs", Context.MODE_PRIVATE)

        // REGRA NUCLEAR: Imprimir os dados das primeiras 5 tentativas, sem exceção.
        val attemptCount = prefs.getInt("launchAttemptCount", 0) + 1
        prefs.edit().putInt("launchAttemptCount", attemptCount).apply()
        
        if (attemptCount <= 5) {
            Log.d("FocusGuardNuclear", "========================================")
            Log.d("FocusGuardNuclear", "OPÇÃO NUCLEAR: Inicializando o FocusGuard v2")
            Log.d("FocusGuardNuclear", "Tentativa de inicialização: $attemptCount")
            Log.d("FocusGuardNuclear", "Pacote ativo: $packageName")
            Log.d("FocusGuardNuclear", "========================================")
        }

        if (!prefs.getBoolean("hasSeenOnboarding", false)) {
            startActivity(Intent(this, PermissionsActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        viewPager.adapter = MainPagerAdapter(this)
        viewPager.offscreenPageLimit = 1
    }
}
