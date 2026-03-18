package com.focusguard.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.focusguard.R
import com.focusguard.database.AppDatabase
import com.focusguard.database.BlockedApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SelectableApp(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    var isSelected: Boolean = false,
    val isSuggested: Boolean = false
)

class AppSelectionActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: View
    private lateinit var adapter: AppSelectionAdapter
    private lateinit var database: AppDatabase

    private val suggestedAddictiveApps = listOf(
        Pair("com.google.android.youtube", "YouTube"),
        Pair("com.zhiliaoapp.musically", "TikTok"),
        Pair("com.instagram.android", "Instagram"),
        Pair("com.facebook.katana", "Facebook"),
        Pair("com.twitter.android", "X (Twitter)"),
        Pair("com.reddit.frontpage", "Reddit"),
        Pair("com.whatsapp", "WhatsApp")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_selection)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = "Selecionar Aplicativos"
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        database = AppDatabase.getDatabase(this)
        progressBar = findViewById(R.id.progressBar)
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = AppSelectionAdapter()
        recyclerView.adapter = adapter

        loadApps()
    }

    private fun loadApps() {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = packageManager
                val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                val appList = mutableListOf<SelectableApp>()

                // Get already blocked apps to check them
                val blockedApps = database.blockedAppDao().getAllBlockedApps()
                val blockedPackageNames = blockedApps.map { it.packageName }.toSet()

                // Get all generic launcher mapped packages in 1 fast IPC Query (3~7s Speed Boost)
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val launchables = pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.toSet()

                // 1. Add Installed Apps (only launchable ones)
                for (info in installedApps) {
                    if (launchables.contains(info.packageName)) {
                        val appName = info.loadLabel(pm).toString()
                        val icon = try { info.loadIcon(pm) } catch (_: Exception) { null }
                        appList.add(
                            SelectableApp(
                                packageName = info.packageName,
                                appName = appName,
                                icon = icon,
                                isSelected = blockedPackageNames.contains(info.packageName),
                                isSuggested = false
                            )
                        )
                    }
                }

                // 2. Inject Suggested Apps (if not already listed)
                val installedPackageNames = appList.map { it.packageName }.toSet()
                for (suggested in suggestedAddictiveApps) {
                    if (!installedPackageNames.contains(suggested.first)) {
                        appList.add(
                            SelectableApp(
                                packageName = suggested.first,
                                appName = suggested.second + " (Não instalado)",
                                icon = null,
                                isSelected = blockedPackageNames.contains(suggested.first),
                                isSuggested = true
                            )
                        )
                    }
                }

                appList.sortBy { it.appName.lowercase() }
                appList
            }

            adapter.submitList(apps)
            progressBar.visibility = View.GONE
        }
    }

    override fun onPause() {
        super.onPause()
        saveSelection()
    }

    private fun saveSelection() {
        val selectedApps = adapter.getSelectedApps()
        val selectedPackageNames = selectedApps.map { it.packageName }.toSet()

        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val dao = database.blockedAppDao()
            val existingBlocked = dao.getAllBlockedApps()
            val existingPackageNames = existingBlocked.map { it.packageName }.toSet()

            // Remove apps that were deselected
            for (existing in existingBlocked) {
                if (!selectedPackageNames.contains(existing.packageName)) {
                    dao.deleteBlockedApp(existing)
                }
            }

            // Add newly selected apps
            for (app in selectedApps) {
                if (!existingPackageNames.contains(app.packageName)) {
                    dao.insertBlockedApp(
                        BlockedApp(
                            packageName = app.packageName,
                            appName = app.appName
                        )
                    )
                }
            }
        }
    }
}

class AppSelectionAdapter : RecyclerView.Adapter<AppSelectionAdapter.ViewHolder>() {

    private var apps = listOf<SelectableApp>()

    fun submitList(newApps: List<SelectableApp>) {
        apps = newApps
        notifyDataSetChanged()
    }

    fun getSelectedApps(): List<SelectableApp> = apps.filter { it.isSelected }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_selection, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.tvAppName.text = app.appName
        holder.tvAppPackage.text = app.packageName

        if (app.icon != null) {
            holder.iconApp.setImageDrawable(app.icon)
        } else {
            holder.iconApp.setImageResource(android.R.drawable.sym_def_app_icon)
        }

        // Remove listener to avoid triggers during recycling
        holder.chkSelect.setOnCheckedChangeListener(null)
        holder.chkSelect.isChecked = app.isSelected

        holder.chkSelect.setOnCheckedChangeListener { _, isChecked ->
            app.isSelected = isChecked
        }

        holder.itemView.setOnClickListener {
            holder.chkSelect.isChecked = !holder.chkSelect.isChecked
        }
    }

    override fun getItemCount() = apps.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val iconApp: ImageView = view.findViewById(R.id.iconApp)
        val tvAppName: TextView = view.findViewById(R.id.tvAppName)
        val tvAppPackage: TextView = view.findViewById(R.id.tvAppPackage)
        val chkSelect: CheckBox = view.findViewById(R.id.chkSelect)
    }
}
