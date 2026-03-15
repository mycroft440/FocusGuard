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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BlockingSessionStatusFragment : BottomSheetDialogFragment() {

    private lateinit var statusTextView: TextView
    private lateinit var detailsTextView: TextView
    private lateinit var renounceButton: Button
    private lateinit var closeButton: Button
    private lateinit var sessionManager: BlockingSessionManager
    private lateinit var deviceOwnerManager: DeviceOwnerManager

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null
    private var currentUpdateJob: Job? = null

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

        startUpdatingStatus()
    }

    private fun startUpdatingStatus() {
        updateRunnable = object : Runnable {
            override fun run() {
                if (isAdded) {
                    updateStatus()
                    handler.postDelayed(this, 2000) // Update every 2 seconds (reduced from 1s)
                }
            }
        }
        handler.post(updateRunnable!!)
    }

    private fun updateStatus() {
        // Cancel previous update if still running to prevent accumulation
        currentUpdateJob?.cancel()
        currentUpdateJob = scope.launch {
            try {
                if (!isAdded) return@launch

                val isBlocking = sessionManager.isBlockingActive()
                val hasSession = sessionManager.hasRegisteredSession()
                val details = sessionManager.getSessionDetails()

                if (isBlocking) {
                    statusTextView.text = "Bloqueio Ativo"
                    statusTextView.setTextColor(requireContext().getColor(android.R.color.holo_red_light))
                } else if (hasSession) {
                    statusTextView.text = "Sessão Registrada (Aguardando janela)"
                    statusTextView.setTextColor(requireContext().getColor(android.R.color.holo_orange_light))
                } else {
                    statusTextView.text = "Nenhuma Sessão Ativa"
                    statusTextView.setTextColor(requireContext().getColor(android.R.color.holo_green_light))
                }

                detailsTextView.text = details

                renounceButton.isEnabled = !hasSession
                renounceButton.alpha = if (!hasSession) 1f else 0.5f

                renounceButton.text = if (isBlocking) {
                    "Não é possível revogar (Bloqueio ativo)"
                } else {
                    "Revogar Device Owner"
                }
            } catch (e: Exception) {
                if (isAdded) {
                    statusTextView.text = "Erro: ${e.message}"
                }
            }
        }
    }

    private fun renounceDeviceOwner() {
        scope.launch {
            val hasSession = sessionManager.hasRegisteredSession()

            if (hasSession) {
                Toast.makeText(
                    requireContext(),
                    "Não é possível revogar enquanto uma sessão está registrada.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            android.app.AlertDialog.Builder(requireContext())
                .setTitle("Revogar Modo Device Owner?")
                .setMessage(
                    "Você está prestes a revogar o Modo Device Owner.\n\n" +
                            "IMPORTANTE:\n" +
                            "- FocusGuard não será mais o Device Owner\n" +
                            "- Isso só é possível porque nenhuma sessão está ativa.\n" +
                            "Tem certeza?"
                )
                .setPositiveButton("Sim, Revogar") { _, _ ->
                    performRenounce()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun performRenounce() {
        scope.launch {
            try {
                deviceOwnerManager.renounceDeviceOwner()

                if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        "Modo Device Owner revogado com sucesso.",
                        Toast.LENGTH_LONG
                    ).show()
                    updateStatus()
                }
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        "Erro: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        updateRunnable?.let { handler.removeCallbacks(it) }
        currentUpdateJob?.cancel()
        job.cancel()
    }
}
