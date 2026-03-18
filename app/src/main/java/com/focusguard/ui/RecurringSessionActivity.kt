package com.focusguard.ui

import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.focusguard.R
import com.focusguard.database.AppDatabase
import com.focusguard.manager.BlockingSessionManager
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.Dispatchers
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
    private lateinit var layoutSelectedApps: LinearLayout
    private lateinit var layoutSelectedSites: LinearLayout
    private lateinit var btnStartSession: Button

    private lateinit var sessionManager: BlockingSessionManager

    private var startHour = -1
    private var startMinute = -1
    private var endHour = -1
    private var endMinute = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // VERIFICAÇÃO DE SEGURANÇA
        val sessionCheckManager = BlockingSessionManager(this)
        kotlinx.coroutines.runBlocking {
            if (sessionCheckManager.hasRegisteredSession()) {
                Toast.makeText(this@RecurringSessionActivity, "Acesso negado: Há uma sessão ativa.", Toast.LENGTH_LONG).show()
                finish()
                return@runBlocking
            }
        }

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
        layoutSelectedApps = findViewById(R.id.layoutSelectedApps)
        layoutSelectedSites = findViewById(R.id.layoutSelectedSites)
        btnStartSession = findViewById(R.id.btnStartSession)

        toggleDays = listOf(
            findViewById(R.id.btnSun), findViewById(R.id.btnMon),
            findViewById(R.id.btnTue), findViewById(R.id.btnWed),
            findViewById(R.id.btnThu), findViewById(R.id.btnFri),
            findViewById(R.id.btnSat)
        )

        setupTimePickers()

        btnSelectApps.setOnClickListener {
            startActivity(Intent(this, AppSelectionActivity::class.java))
        }

        btnSelectSites.setOnClickListener {
            startActivity(Intent(this, WebsiteSelectionActivity::class.java))
        }

        btnStartSession.setOnClickListener {
            saveRecurringSession()
        }

        // Limpa seleções de sessões anteriores ao abrir
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@RecurringSessionActivity)
            db.blockedAppDao().deleteAllBlockedApps()
            db.blockedWebsiteDao().deleteAllBlockedWebsites()

            withContext(Dispatchers.Main) {
                updateCounts()
            }
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
            val apps = withContext(Dispatchers.IO) {
                db.blockedAppDao().getAllBlockedApps()
            }
            val sites = withContext(Dispatchers.IO) {
                db.blockedWebsiteDao().getAllBlockedWebsites()
            }
            tvSelectedAppsCount.text = "${apps.size} apps selecionados"
            tvSelectedSitesCount.text = "${sites.size} sites selecionados"

            // Mostrar ícones dos apps selecionados
            layoutSelectedApps.removeAllViews()
            val pm = packageManager
            val iconSizePx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 36f, resources.displayMetrics
            ).toInt()
            val marginPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 4f, resources.displayMetrics
            ).toInt()

            for (app in apps) {
                try {
                    val icon = pm.getApplicationIcon(app.packageName)
                    val imageView = ImageView(this@RecurringSessionActivity).apply {
                        setImageDrawable(icon)
                        layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx).apply {
                            setMargins(marginPx, 0, marginPx, 0)
                        }
                        contentDescription = app.appName
                    }
                    layoutSelectedApps.addView(imageView)
                } catch (_: PackageManager.NameNotFoundException) {
                    // App não encontrado no dispositivo
                }
            }

            // Mostrar labels dos sites selecionados
            layoutSelectedSites.removeAllViews()
            val badgePaddingH = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 10f, resources.displayMetrics
            ).toInt()
            val badgePaddingV = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 4f, resources.displayMetrics
            ).toInt()

            for (site in sites) {
                val badge = TextView(this@RecurringSessionActivity).apply {
                    text = site.domain
                    setTextColor(Color.parseColor("#FF00BCD4"))
                    textSize = 11f
                    setTypeface(null, Typeface.BOLD)
                    setBackgroundResource(R.drawable.toggle_bg)
                    setPadding(badgePaddingH, badgePaddingV, badgePaddingH, badgePaddingV)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(marginPx, 0, marginPx, 0)
                    }
                    gravity = Gravity.CENTER
                }
                layoutSelectedSites.addView(badge)
            }
        }
    }

    private fun setupTimePickers() {
        btnStartTime.setOnClickListener {
            val calendar = Calendar.getInstance()
            TimePickerDialog(this, { _, h, m ->
                startHour = h
                startMinute = m
                btnStartTime.text = String.format(Locale.getDefault(), "Início: %02d:%02d", h, m)
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show()
        }

        btnEndTime.setOnClickListener {
            val calendar = Calendar.getInstance()
            TimePickerDialog(this, { _, h, m ->
                endHour = h
                endMinute = m
                btnEndTime.text = String.format(Locale.getDefault(), "Fim: %02d:%02d", h, m)
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show()
        }
    }

    private fun saveRecurringSession() {
        if (startHour == -1 || endHour == -1) {
            Toast.makeText(this, "Defina os horários de início e fim", Toast.LENGTH_SHORT).show()
            return
        }

        if (startHour == endHour && startMinute == endMinute) {
            Toast.makeText(this, "Horário de início e fim não podem ser iguais", Toast.LENGTH_SHORT).show()
            return
        }

        val monthsStr = editDurationMonths.text.toString().trim()
        val durationMonths = monthsStr.toIntOrNull() ?: 1
        if (durationMonths <= 0) {
            Toast.makeText(this, "Duração deve ser pelo menos 1 mês", Toast.LENGTH_SHORT).show()
            return
        }

        val selectedDays = mutableListOf<Int>()
        toggleDays.forEachIndexed { index, toggleButton ->
            if (toggleButton.isChecked) {
                selectedDays.add(index + 1)
            }
        }

        if (selectedDays.isEmpty()) {
            Toast.makeText(this, "Selecione pelo menos um dia da semana", Toast.LENGTH_SHORT).show()
            return
        }

        val daysString = selectedDays.joinToString(",")

        btnStartSession.isEnabled = false

        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(this@RecurringSessionActivity)
            val appsCount = withContext(Dispatchers.IO) {
                db.blockedAppDao().getAllBlockedApps().size
            }
            val sitesCount = withContext(Dispatchers.IO) {
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
