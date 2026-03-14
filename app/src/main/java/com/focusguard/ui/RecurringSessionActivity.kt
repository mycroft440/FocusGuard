package com.focusguard.ui

import android.app.TimePickerDialog
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.focusguard.R
import com.focusguard.manager.BlockingSessionManager
import java.util.Calendar
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecurringSessionActivity : AppCompatActivity() {

    private lateinit var btnStartTime: Button
    private lateinit var btnEndTime: Button
    private lateinit var toggleDays: List<ToggleButton>
    private lateinit var editDurationMonths: EditText
    private lateinit var btnSelectApps: Button
    private lateinit var btnSelectSites: Button
    private lateinit var tvSelectedAppsCount: TextView
    private lateinit var tvSelectedSitesCount: TextView
    private lateinit var btnStartSession: Button

    private lateinit var sessionManager: BlockingSessionManager

    private var startHour = -1
    private var startMinute = -1
    private var endHour = -1
    private var endMinute = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recurring_session)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = "Sessão Recorrente"
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        sessionManager = BlockingSessionManager(this)

        btnStartTime = findViewById(R.id.btnStartTime)
        btnEndTime = findViewById(R.id.btnEndTime)
        editDurationMonths = findViewById(R.id.editDurationMonths)
        btnSelectApps = findViewById(R.id.btnSelectApps)
        btnSelectSites = findViewById(R.id.btnSelectSites)
        tvSelectedAppsCount = findViewById(R.id.tvSelectedAppsCount)
        tvSelectedSitesCount = findViewById(R.id.tvSelectedSitesCount)
        btnStartSession = findViewById(R.id.btnStartSession)

        toggleDays = listOf(
            findViewById(R.id.btnSun), findViewById(R.id.btnMon),
            findViewById(R.id.btnTue), findViewById(R.id.btnWed),
            findViewById(R.id.btnThu), findViewById(R.id.btnFri),
            findViewById(R.id.btnSat)
        )

        setupTimePickers()

        btnSelectApps.setOnClickListener {
            startActivity(android.content.Intent(this, AppSelectionActivity::class.java))
        }

        btnSelectSites.setOnClickListener {
            startActivity(android.content.Intent(this, WebsiteSelectionActivity::class.java))
        }

        btnStartSession.setOnClickListener {
            saveRecurringSession()
        }
    }

    override fun onResume() {
        super.onResume()
        updateCounts()
    }

    private fun updateCounts() {
        val db = com.focusguard.database.AppDatabase.getDatabase(this)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val appsCount = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                db.blockedAppDao().getAllBlockedApps().size
            }
            val sitesCount = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                db.blockedWebsiteDao().getAllBlockedWebsites().size
            }
            tvSelectedAppsCount.text = "$appsCount apps selecionados"
            tvSelectedSitesCount.text = "$sitesCount sites selecionados"
        }
    }

    private fun setupTimePickers() {
        btnStartTime.setOnClickListener {
            val calendar = Calendar.getInstance()
            TimePickerDialog(this, { _, h, m ->
                startHour = h
                startMinute = m
                btnStartTime.text = String.format("Início: %02d:%02d", h, m)
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show()
        }

        btnEndTime.setOnClickListener {
            val calendar = Calendar.getInstance()
            TimePickerDialog(this, { _, h, m ->
                endHour = h
                endMinute = m
                btnEndTime.text = String.format("Fim: %02d:%02d", h, m)
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show()
        }
    }

    private fun saveRecurringSession() {
        if (startHour == -1 || endHour == -1) {
            Toast.makeText(this, "Defina os horários de início e fim", Toast.LENGTH_SHORT).show()
            return
        }

        val monthsStr = editDurationMonths.text.toString()
        val durationMonths = if (monthsStr.isNotEmpty()) monthsStr.toInt() else 1

        val selectedDays = mutableListOf<Int>()
        toggleDays.forEachIndexed { index, toggleButton ->
            if (toggleButton.isChecked) {
                selectedDays.add(index + 1) // 1=Sun, 2=Mon...
            }
        }

        if (selectedDays.isEmpty()) {
            Toast.makeText(this, "Selecione pelo menos um dia da semana", Toast.LENGTH_SHORT).show()
            return
        }

        val daysString = selectedDays.joinToString(",")

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val db = com.focusguard.database.AppDatabase.getDatabase(this@RecurringSessionActivity)
            val appsCount = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                db.blockedAppDao().getAllBlockedApps().size
            }
            val sitesCount = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                db.blockedWebsiteDao().getAllBlockedWebsites().size
            }

            sessionManager.startRecurringSession(
                startHour,
                startMinute,
                endHour,
                endMinute,
                daysString,
                durationMonths,
                appsCount,
                sitesCount
            )
            finish()
        }
    }
}
