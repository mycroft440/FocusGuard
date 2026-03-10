package com.focusguard.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.focusguard.R
import com.focusguard.database.BlockedApp

class BlockedAppsAdapter(
    private val blockedApps: MutableList<BlockedApp>,
    private val onRemove: (BlockedApp) -> Unit
) : RecyclerView.Adapter<BlockedAppsAdapter.ViewHolder>() {

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val appNameTextView: TextView = itemView.findViewById(R.id.appNameTextView)
        private val packageNameTextView: TextView = itemView.findViewById(R.id.packageNameTextView)
        private val removeButton: Button = itemView.findViewById(R.id.removeButton)

        fun bind(blockedApp: BlockedApp) {
            appNameTextView.text = blockedApp.appName
            packageNameTextView.text = blockedApp.packageName
            removeButton.setOnClickListener {
                onRemove(blockedApp)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_blocked_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(blockedApps[position])
    }

    override fun getItemCount(): Int = blockedApps.size

    fun updateList(newList: MutableList<BlockedApp>) {
        blockedApps.clear()
        blockedApps.addAll(newList)
        notifyDataSetChanged()
    }
}
