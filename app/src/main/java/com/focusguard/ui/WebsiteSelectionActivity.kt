package com.focusguard.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.focusguard.R
import com.focusguard.database.AppDatabase
import com.focusguard.database.BlockedWebsite
import com.google.android.material.chip.Chip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WebsiteSelectionActivity : AppCompatActivity() {

    private lateinit var editUrl: EditText
    private lateinit var btnAddSite: Button
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: WebsiteSelectionAdapter
    private lateinit var database: AppDatabase
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_website_selection)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = "Selecionar Sites"
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        database = AppDatabase.getDatabase(this)
        editUrl = findViewById(R.id.editUrl)
        btnAddSite = findViewById(R.id.btnAddSite)
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        adapter = WebsiteSelectionAdapter { site ->
            removeSite(site)
        }
        recyclerView.adapter = adapter

        setupChips()
        
        btnAddSite.setOnClickListener {
            val url = editUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                addSite(url)
                editUrl.text.clear()
            } else {
                Toast.makeText(this, "Digite uma URL válida", Toast.LENGTH_SHORT).show()
            }
        }

        loadSites()
    }

    private fun setupChips() {
        val chips = listOf(R.id.chipYoutube, R.id.chipInstagram, R.id.chipFacebook, R.id.chipReddit)
        for (chipId in chips) {
            val chip = findViewById<Chip>(chipId)
            chip.setOnClickListener {
                addSite(chip.text.toString())
            }
        }
    }

    private fun loadSites() {
        scope.launch {
            val sites = withContext(Dispatchers.IO) {
                database.blockedWebsiteDao().getAllBlockedWebsites()
            }
            adapter.submitList(sites.toMutableList())
        }
    }

    private fun addSite(domain: String) {
        val cleanDomain = domain.replace("https://", "").replace("http://", "").replace("www.", "")
        scope.launch {
            withContext(Dispatchers.IO) {
                val existing = database.blockedWebsiteDao().getBlockedWebsiteByDomain(cleanDomain)
                if (existing == null) {
                    val newSite = BlockedWebsite(
                        domain = cleanDomain,
                        url = "https://$cleanDomain",
                        isBlocked = true
                    )
                    database.blockedWebsiteDao().insertBlockedWebsite(newSite)
                }
            }
            loadSites() // Recarrega a lista do banco
        }
    }

    private fun removeSite(site: BlockedWebsite) {
        scope.launch {
            withContext(Dispatchers.IO) {
                database.blockedWebsiteDao().deleteBlockedWebsite(site)
            }
            loadSites()
        }
    }
}

class WebsiteSelectionAdapter(private val onRemoveClick: (BlockedWebsite) -> Unit) : RecyclerView.Adapter<WebsiteSelectionAdapter.ViewHolder>() {

    private var sites = mutableListOf<BlockedWebsite>()

    fun submitList(newSites: MutableList<BlockedWebsite>) {
        sites = newSites
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_website_selection, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val site = sites[position]
        holder.tvUrl.text = site.domain
        holder.btnRemove.setOnClickListener {
            onRemoveClick(site)
        }
    }

    override fun getItemCount() = sites.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvUrl: TextView = view.findViewById(R.id.tvUrl)
        val btnRemove: ImageButton = view.findViewById(R.id.btnRemove)
    }
}
