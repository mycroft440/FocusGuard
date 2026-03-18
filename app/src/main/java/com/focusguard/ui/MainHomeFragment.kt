package com.focusguard.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.focusguard.R
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.utils.PermissionUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class MainHomeFragment : Fragment() {

    private lateinit var btnPendingPermissions: MaterialButton
    private lateinit var cardTimeSession: MaterialCardView
    private lateinit var cardRecurringSession: MaterialCardView
    private lateinit var btnActiveSessions: MaterialButton
    private lateinit var btnDeviceOwnerTutorial: MaterialButton
    private lateinit var deviceOwnerManager: DeviceOwnerManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_main_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        deviceOwnerManager = DeviceOwnerManager(requireContext())

        btnPendingPermissions = view.findViewById(R.id.btnPendingPermissions)
        cardTimeSession = view.findViewById(R.id.cardTimeSession)
        cardRecurringSession = view.findViewById(R.id.cardRecurringSession)
        btnActiveSessions = view.findViewById(R.id.btnActiveSessions)
        btnDeviceOwnerTutorial = view.findViewById(R.id.btnDeviceOwnerTutorial)

        btnPendingPermissions.setOnClickListener {
            startActivity(Intent(requireContext(), PermissionsActivity::class.java))
        }

        cardTimeSession.setOnClickListener {
            startActivity(Intent(requireContext(), TimeSessionActivity::class.java))
        }

        cardRecurringSession.setOnClickListener {
            startActivity(Intent(requireContext(), RecurringSessionActivity::class.java))
        }

        btnActiveSessions.setOnClickListener {
            val fragment = BlockingSessionStatusFragment()
            fragment.show(parentFragmentManager, "BlockingSessionStatus")
        }

        btnDeviceOwnerTutorial.setOnClickListener {
            deviceOwnerManager.setAsDeviceOwner()
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndBanner()
    }

    private fun checkPermissionsAndBanner() {
        val ctx = context ?: return
        val isA11yEnabled = PermissionUtils.isAccessibilityServiceEnabled(ctx)
        val isAdminActive = deviceOwnerManager.isDeviceAdminActive() || deviceOwnerManager.isDeviceOwnerActive()
        val isUsageAccessEnabled = PermissionUtils.isUsageAccessEnabled(ctx)

        if (!isA11yEnabled || !isAdminActive || !isUsageAccessEnabled) {
            btnPendingPermissions.visibility = View.VISIBLE
        } else {
            btnPendingPermissions.visibility = View.GONE
        }
    }
}
