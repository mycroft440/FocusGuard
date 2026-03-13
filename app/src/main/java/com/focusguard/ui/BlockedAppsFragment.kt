package com.focusguard.ui

import android.app.AlertDialog
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
    private lateinit var quickBlockButton: Button
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
        quickBlockButton = view.findViewById(R.id.quickBlockButton)

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

        // Quick block famous apps (pre-emptive blocking)
        quickBlockButton.setOnClickListener {
            showQuickBlockFamousAppsDialog()
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
        val pm = requireContext().packageManager
        scope.launch {
            val apps = withContext(Dispatchers.IO) {
                val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                installedApps.filter { app ->
                    (app.flags and ApplicationInfo.FLAG_SYSTEM == 0) && app.packageName != requireContext().packageName
                }.map { app ->
                    Pair(pm.getApplicationLabel(app).toString(), app.packageName)
                }.sortedBy { it.first }
            }

            if (apps.isEmpty()) {
                Toast.makeText(requireContext(), "No apps found to block", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val appNames = apps.map { it.first }.toTypedArray()
            
            AlertDialog.Builder(requireContext())
                .setTitle("Select Installed App to Block")
                .setItems(appNames) { _, index ->
                    val selected = apps[index]
                    addBlockedApp(selected.first, selected.second)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun showQuickBlockFamousAppsDialog() {
        val famousApps = listOf(
            Pair("Instagram", "com.instagram.android"),
            Pair("TikTok", "com.zhiliaoapp.musically"),
            Pair("YouTube", "com.google.android.youtube"),
            Pair("Facebook", "com.facebook.katana"),
            Pair("Twitter / X", "com.twitter.android"),
            Pair("Reddit", "com.reddit.frontpage"),
            Pair("Tinder", "com.tinder"),
            Pair("Snapchat", "com.snapchat.android"),
            Pair("Pinterest", "com.pinterest"),
            Pair("Kwai", "com.kwai.video"),
            Pair("Twitch", "tv.twitch.android.app"),
            Pair("Discord", "com.discord"),
            Pair("Netflix", "com.netflix.mediaclient"),
            Pair("Roblox", "com.roblox.client"),
            Pair("Free Fire", "com.dts.freefireth"),
            Pair("Bumble", "com.bumble.app"),
            Pair("Badoo", "com.badoo.mobile"),
            Pair("WhatsApp", "com.whatsapp"),
            Pair("Telegram", "org.telegram.messenger"),
            Pair("LinkedIn", "com.linkedin.android")
        )

        val appNames = famousApps.map { it.first }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("Quick Block (Prevents Installation/Usage)")
            .setMessage("Select an app to block. It will be restricted even if you haven't installed it yet!")
            .setItems(appNames) { _, index ->
                val selected = famousApps[index]
                addBlockedApp(selected.first, selected.second)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addBlockedApp(appName: String, packageName: String) {
        scope.launch {
            withContext(Dispatchers.IO) {
                val existing = database.blockedAppDao().getBlockedAppByPackage(packageName)
                if (existing == null) {
                    database.blockedAppDao().insertBlockedApp(
                        BlockedApp(
                            packageName = packageName,
                            appName = appName,
                            isBlocked = true
                        )
                    )
                }
            }
            loadBlockedApps()
            Toast.makeText(requireContext(), "App blocked: $appName", Toast.LENGTH_SHORT).show()
        }
    }
}
