package com.focusguard.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.focusguard.R
import com.focusguard.adapter.BlockedAppsAdapter
import com.focusguard.database.AppDatabase
import com.focusguard.database.BlockedApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BlockedAppsFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var addAppButton: Button
    private lateinit var database: AppDatabase
    private lateinit var adapter: BlockedAppsAdapter
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_blocked_apps, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        database = AppDatabase.getDatabase(requireContext())
        recyclerView = view.findViewById(R.id.blockedAppsRecyclerView)
        addAppButton = view.findViewById(R.id.addAppButton)

        // Setup RecyclerView
        adapter = BlockedAppsAdapter(mutableListOf()) { blockedApp ->
            removeBlockedApp(blockedApp)
        }
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        // Load blocked apps
        loadBlockedApps()

        // Add app button click listener
        addAppButton.setOnClickListener {
            showAppSelectionDialog()
        }
    }

    private fun loadBlockedApps() {
        scope.launch {
            val blockedApps = withContext(Dispatchers.IO) {
                database.blockedAppDao().getAllBlockedApps()
            }
            adapter.updateList(blockedApps.toMutableList())
        }
    }

    private fun removeBlockedApp(blockedApp: BlockedApp) {
        scope.launch {
            withContext(Dispatchers.IO) {
                database.blockedAppDao().deleteBlockedApp(blockedApp)
            }
            loadBlockedApps()
            Toast.makeText(requireContext(), "App unblocked", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAppSelectionDialog() {
        val packageManager = requireContext().packageManager
        val apps = mutableListOf<Pair<String, String>>()

        val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        for (app in installedApps) {
            if (app.flags and ApplicationInfo.FLAG_SYSTEM == 0) {
                val appName = packageManager.getApplicationLabel(app).toString()
                apps.add(Pair(appName, app.packageName))
            }
        }

        // Simple dialog to select app (you can implement a proper dialog later)
        if (apps.isNotEmpty()) {
            val selectedApp = apps[0]
            addBlockedApp(selectedApp.first, selectedApp.second)
        }
    }

    private fun addBlockedApp(appName: String, packageName: String) {
        scope.launch {
            withContext(Dispatchers.IO) {
                database.blockedAppDao().insertBlockedApp(
                    BlockedApp(
                        packageName = packageName,
                        appName = appName,
                        isBlocked = true
                    )
                )
            }
            loadBlockedApps()
            Toast.makeText(requireContext(), "App blocked", Toast.LENGTH_SHORT).show()
        }
    }
}
