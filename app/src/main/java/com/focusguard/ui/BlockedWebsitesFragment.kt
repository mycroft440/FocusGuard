package com.focusguard.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.focusguard.R
import com.focusguard.adapter.BlockedWebsitesAdapter
import com.focusguard.database.AppDatabase
import com.focusguard.database.BlockedWebsite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BlockedWebsitesFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var addWebsiteButton: Button
    private lateinit var websiteInput: EditText
    private lateinit var database: AppDatabase
    private lateinit var adapter: BlockedWebsitesAdapter
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_blocked_websites, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        database = AppDatabase.getDatabase(requireContext())
        recyclerView = view.findViewById(R.id.blockedWebsitesRecyclerView)
        addWebsiteButton = view.findViewById(R.id.addWebsiteButton)
        websiteInput = view.findViewById(R.id.websiteInput)

        // Setup RecyclerView
        adapter = BlockedWebsitesAdapter(mutableListOf()) { blockedWebsite ->
            removeBlockedWebsite(blockedWebsite)
        }
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        // Load blocked websites
        loadBlockedWebsites()

        // Add website button click listener
        addWebsiteButton.setOnClickListener {
            val domain = websiteInput.text.toString().trim()
            if (domain.isNotEmpty()) {
                addBlockedWebsite(domain)
                websiteInput.text.clear()
            } else {
                Toast.makeText(requireContext(), "Please enter a domain", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadBlockedWebsites() {
        scope.launch {
            val blockedWebsites = withContext(Dispatchers.IO) {
                database.blockedWebsiteDao().getAllBlockedWebsites()
            }
            adapter.updateList(blockedWebsites.toMutableList())
        }
    }

    private fun removeBlockedWebsite(blockedWebsite: BlockedWebsite) {
        scope.launch {
            withContext(Dispatchers.IO) {
                database.blockedWebsiteDao().deleteBlockedWebsite(blockedWebsite)
            }
            loadBlockedWebsites()
            Toast.makeText(requireContext(), "Website unblocked", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addBlockedWebsite(domain: String) {
        scope.launch {
            withContext(Dispatchers.IO) {
                database.blockedWebsiteDao().insertBlockedWebsite(
                    BlockedWebsite(
                        domain = domain,
                        url = "https://$domain",
                        isBlocked = true
                    )
                )
            }
            loadBlockedWebsites()
            Toast.makeText(requireContext(), "Website blocked", Toast.LENGTH_SHORT).show()
        }
    }
}
