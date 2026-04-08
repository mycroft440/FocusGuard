package com.focusguard.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.focusguard.database.AppDatabase
import com.focusguard.database.BlockedWebsite
import com.focusguard.ui.compose.screens.WebsiteSelectionScreen
import com.focusguard.ui.compose.theme.FocusGuardTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WebsiteSelectionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val database = AppDatabase.getDatabase(this)
        val activity = this

        setContent {
            FocusGuardTheme {
                var sites by remember { mutableStateOf<List<BlockedWebsite>>(emptyList()) }
                val scope = rememberCoroutineScope()

                // Load sites
                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) {
                        val loaded = database.blockedWebsiteDao().getAllBlockedWebsites()
                        withContext(Dispatchers.Main) {
                            sites = loaded
                        }
                    }
                }

                fun refreshSites() {
                    scope.launch(Dispatchers.IO) {
                        val loaded = database.blockedWebsiteDao().getAllBlockedWebsites()
                        withContext(Dispatchers.Main) {
                            sites = loaded
                        }
                    }
                }

                WebsiteSelectionScreen(
                    sites = sites,
                    onAddSite = { domain ->
                        val cleanedDomain = cleanDomain(domain)
                        if (cleanedDomain.isEmpty() || !cleanedDomain.contains(".") || cleanedDomain.length <= 4) {
                            Toast.makeText(activity, "Domínio inválido ou muito curto", Toast.LENGTH_SHORT).show()
                            return@WebsiteSelectionScreen
                        }
                        scope.launch(Dispatchers.IO) {
                            val existing = database.blockedWebsiteDao().getBlockedWebsiteByDomain(cleanedDomain)
                            if (existing == null) {
                                database.blockedWebsiteDao().insertBlockedWebsite(
                                    BlockedWebsite(
                                        domain = cleanedDomain,
                                        url = "https://$cleanedDomain",
                                        isBlocked = true
                                    )
                                )
                            }
                            val loaded = database.blockedWebsiteDao().getAllBlockedWebsites()
                            withContext(Dispatchers.Main) {
                                sites = loaded
                            }
                        }
                    },
                    onRemoveSite = { site ->
                        scope.launch(Dispatchers.IO) {
                            database.blockedWebsiteDao().deleteBlockedWebsite(site)
                            val loaded = database.blockedWebsiteDao().getAllBlockedWebsites()
                            withContext(Dispatchers.Main) {
                                sites = loaded
                            }
                        }
                    },
                    onBack = { finish() }
                )
            }
        }
    }

    private fun cleanDomain(input: String): String {
        return input.trim().lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .split("/")[0]
            .split("?")[0]
            .split(":")[0]
    }
}
