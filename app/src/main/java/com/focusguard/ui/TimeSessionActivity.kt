package com.focusguard.ui

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
    private lateinit var layoutSelectedApps: LinearLayout
    private lateinit var layoutSelectedSites: LinearLayout
    private lateinit var sessionManager: BlockingSessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // VERIFICAÇÃO DE SEGURANÇA
        val sessionCheckManager = BlockingSessionManager(this)
        kotlinx.coroutines.runBlocking {
            if (sessionCheckManager.hasRegisteredSession()) {
                Toast.makeText(this@TimeSessionActivity, "Acesso negado: Há uma sessão ativa.", Toast.LENGTH_LONG).show()
                finish()
                return@runBlocking
            }
        }

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
        layoutSelectedApps = findViewById(R.id.layoutSelectedApps)
        layoutSelectedSites = findViewById(R.id.layoutSelectedSites)
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

        // Limpa seleções de sessões anteriores ao abrir
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@TimeSessionActivity)
            db.blockedAppDao().deleteAllBlockedApps()
            db.blockedWebsiteDao().deleteAllBlockedWebsites()

            withContext(Dispatchers.Main) {
                updateCounts()
            }
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
                    val imageView = ImageView(this@TimeSessionActivity).apply {
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
                val badge = TextView(this@TimeSessionActivity).apply {
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
}
