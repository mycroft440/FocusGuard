package com.focusguard.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.focusguard.R
import com.focusguard.database.AppDatabase
import com.focusguard.manager.BlockingSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TimeSessionActivity : AppCompatActivity() {

    private lateinit var editDays: EditText
    private lateinit var editHours: EditText
    private lateinit var btnSelectApps: Button
    private lateinit var btnSelectSites: Button
    private lateinit var tvSelectedAppsCount: TextView
    private lateinit var tvSelectedSitesCount: TextView
    private lateinit var btnStartSession: Button
    private lateinit var sessionManager: BlockingSessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_time_session)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = "Sessão por Tempo"
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        sessionManager = BlockingSessionManager(this)

        editDays = findViewById(R.id.editDays)
        editHours = findViewById(R.id.editHours)
        btnSelectApps = findViewById(R.id.btnSelectApps)
        btnSelectSites = findViewById(R.id.btnSelectSites)
        tvSelectedAppsCount = findViewById(R.id.tvSelectedAppsCount)
        tvSelectedSitesCount = findViewById(R.id.tvSelectedSitesCount)
        btnStartSession = findViewById(R.id.btnStartSession)

        btnSelectApps.setOnClickListener {
            startActivity(Intent(this, AppSelectionActivity::class.java))
        }

        btnSelectSites.setOnClickListener {
            startActivity(Intent(this, WebsiteSelectionActivity::class.java))
        }

        btnStartSession.setOnClickListener {
            startSession()
        }
    }

    private fun startSession() {
        val daysStr = editDays.text.toString().trim()
        val hoursStr = editHours.text.toString().trim()

        val days = daysStr.toIntOrNull() ?: 0
        val hours = hoursStr.toIntOrNull() ?: 0

        if (days < 0 || hours < 0) {
            Toast.makeText(this, "Valores não podem ser negativos", Toast.LENGTH_SHORT).show()
            return
        }

        if (days == 0 && hours == 0) {
            Toast.makeText(this, "Defina pelo menos 1 hora ou dia", Toast.LENGTH_SHORT).show()
            return
        }

        btnStartSession.isEnabled = false

        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@TimeSessionActivity)
            val appsCount = withContext(Dispatchers.IO) {
                db.blockedAppDao().getAllBlockedApps().size
            }
            val sitesCount = withContext(Dispatchers.IO) {
                db.blockedWebsiteDao().getAllBlockedWebsites().size
            }

            sessionManager.startTimerSession(days, hours, appsCount, sitesCount)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        btnStartSession.isEnabled = true
        updateCounts()
    }

    private fun updateCounts() {
        val db = AppDatabase.getDatabase(this)
        lifecycleScope.launch {
            val appsCount = withContext(Dispatchers.IO) {
                db.blockedAppDao().getAllBlockedApps().size
            }
            val sitesCount = withContext(Dispatchers.IO) {
                db.blockedWebsiteDao().getAllBlockedWebsites().size
            }
            tvSelectedAppsCount.text = "$appsCount apps selecionados"
            tvSelectedSitesCount.text = "$sitesCount sites selecionados"
        }
    }
}
