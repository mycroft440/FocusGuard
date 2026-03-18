package com.focusguard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.focusguard.ui.MainPagerAdapter

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("FocusGuardPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("hasSeenOnboarding", false)) {
            startActivity(Intent(this, com.focusguard.ui.PermissionsActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        viewPager.adapter = MainPagerAdapter(this)
        viewPager.offscreenPageLimit = 1
    }
}
