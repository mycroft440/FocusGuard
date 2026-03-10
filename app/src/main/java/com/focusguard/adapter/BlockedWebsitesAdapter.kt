package com.focusguard.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.focusguard.R
import com.focusguard.database.BlockedWebsite

class BlockedWebsitesAdapter(
    private val blockedWebsites: MutableList<BlockedWebsite>,
    private val onRemove: (BlockedWebsite) -> Unit
) : RecyclerView.Adapter<BlockedWebsitesAdapter.ViewHolder>() {

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val domainTextView: TextView = itemView.findViewById(R.id.domainTextView)
        private val urlTextView: TextView = itemView.findViewById(R.id.urlTextView)
        private val removeButton: Button = itemView.findViewById(R.id.removeButton)

        fun bind(blockedWebsite: BlockedWebsite) {
            domainTextView.text = blockedWebsite.domain
            urlTextView.text = blockedWebsite.url
            removeButton.setOnClickListener {
                onRemove(blockedWebsite)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_blocked_website, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(blockedWebsites[position])
    }

    override fun getItemCount(): Int = blockedWebsites.size

    fun updateList(newList: MutableList<BlockedWebsite>) {
        blockedWebsites.clear()
        blockedWebsites.addAll(newList)
        notifyDataSetChanged()
    }
}
