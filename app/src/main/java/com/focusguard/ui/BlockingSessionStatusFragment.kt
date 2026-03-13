package com.focusguard.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.focusguard.R
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.manager.BlockingSessionManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BlockingSessionStatusFragment : BottomSheetDialogFragment() {

    private lateinit var statusTextView: TextView
    private lateinit var detailsTextView: TextView
    private lateinit var renounceButton: Button
    private lateinit var closeButton: Button
    private lateinit var sessionManager: BlockingSessionManager
    private lateinit var deviceOwnerManager: DeviceOwnerManager
    private val scope = CoroutineScope(Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_blocking_session_status, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sessionManager = BlockingSessionManager(requireContext())
        deviceOwnerManager = DeviceOwnerManager(requireContext())

        statusTextView = view.findViewById(R.id.statusTextView)
        detailsTextView = view.findViewById(R.id.detailsTextView)
        renounceButton = view.findViewById(R.id.renounceButton)
        closeButton = view.findViewById(R.id.closeButton)

        renounceButton.setOnClickListener {
            renounceDeviceOwner()
        }

        closeButton.setOnClickListener {
            dismiss()
        }

        // Start updating status every second
        startUpdatingStatus()
    }

    private fun startUpdatingStatus() {
        updateRunnable = object : Runnable {
            override fun run() {
                if (isAdded) {
                    updateStatus()
                    handler.postDelayed(this, 1000) // Update every second
                }
            }
        }
        handler.post(updateRunnable!!)
    }

    private fun updateStatus() {
        scope.launch {
            try {
                if (!isAdded) return@launch
                
                val isBlocking = sessionManager.isBlockingActive()
                val details = sessionManager.getSessionDetails()

                if (isBlocking) {
                    statusTextView.text = "⏱️ Blocking Active"
                    statusTextView.setTextColor(requireContext().getColor(android.R.color.holo_red_light))
                } else {
                    statusTextView.text = "✓ No Active Blocking"
                    statusTextView.setTextColor(requireContext().getColor(android.R.color.holo_green_light))
                }

                detailsTextView.text = details

                // Update button state: only enable when NO blocking is active
                renounceButton.isEnabled = !isBlocking
                renounceButton.alpha = if (!isBlocking) 1f else 0.5f
                
                // Update button text based on state
                if (isBlocking) {
                    renounceButton.text = "Cannot Renounce (Blocking Active)"
                } else {
                    renounceButton.text = "Renounce Device Owner"
                }
            } catch (e: Exception) {
                statusTextView.text = "Error: ${e.message}"
            }
        }
    }

    private fun renounceDeviceOwner() {
        scope.launch {
            val isBlocking = sessionManager.isBlockingActive()
            
            if (isBlocking) {
                Toast.makeText(
                    requireContext(),
                    "Cannot renounce while blocking is active. Wait for countdown to end.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            // Show warning
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Renounce Device Owner Mode?")
                .setMessage(
                    "You are about to renounce Device Owner Mode natively.\n\n" +
                    "⚠️ IMPORTANT:\n" +
                    "• FocusGuard will no longer be the Device Owner\n" +
                    "• This is only possible because no block session is active.\n" +
                    "Are you sure?"
                )
                .setPositiveButton("Yes, Renounce") { _, _ ->
                    performRenounce()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun performRenounce() {
        scope.launch {
            try {
                deviceOwnerManager.renounceDeviceOwner()
                
                Toast.makeText(
                    requireContext(),
                    "Device Owner Mode renounced successfully.",
                    Toast.LENGTH_LONG
                ).show()

                // The blocking session continues independently (though it should be empty here)
                updateStatus()
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Error: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (updateRunnable != null) {
            handler.removeCallbacks(updateRunnable!!)
        }
    }
}
