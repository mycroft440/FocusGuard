package com.focusguard.adapter

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.focusguard.ui.BlockedAppsFragment
import com.focusguard.ui.BlockedWebsitesFragment

class TabAdapter(fragmentActivity: FragmentActivity) : FragmentStateAdapter(fragmentActivity) {

    override fun getItemCount(): Int = 2

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> BlockedAppsFragment()
            1 -> BlockedWebsitesFragment()
            else -> BlockedAppsFragment()
        }
    }
}
