package com.focusguard.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.focusguard.R
import com.focusguard.database.AppDatabase
import com.focusguard.manager.BlockingSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StartBlockingSessionFragment : Fragment() {

    private lateinit var daysSeekBar: SeekBar
    private lateinit var daysTextView: TextView
    private lateinit var startButton: Button
    private lateinit var cancelButton: Button
    private lateinit var database: AppDatabase
    private lateinit var sessionManager: BlockingSessionManager
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_start_blocking_session, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        database = AppDatabase.getDatabase(requireContext())
        sessionManager = BlockingSessionManager(requireContext())

        daysSeekBar = view.findViewById(R.id.daysSeekBar)
        daysTextView = view.findViewById(R.id.daysTextView)
        startButton = view.findViewById(R.id.startButton)
        cancelButton = view.findViewById(R.id.cancelButton)

        // Setup SeekBar
        daysSeekBar.max = 30 // Max 30 days
        daysSeekBar.progress = 1
        updateDaysText(1)

        daysSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val days = maxOf(1, progress)
                updateDaysText(days)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        startButton.setOnClickListener {
            startBlockingSession()
        }

        cancelButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    private fun updateDaysText(days: Int) {
        daysTextView.text = "Duration: $days day${if (days > 1) "s" else ""}"
    }

    private fun startBlockingSession() {
        scope.launch {
            try {
                val days = daysSeekBar.progress
                val blockedApps = withContext(Dispatchers.IO) {
                    database.blockedAppDao().getAllBlockedApps().size
                }
                val blockedWebsites = withContext(Dispatchers.IO) {
                    database.blockedWebsiteDao().getAllBlockedWebsites().size
                }

                if (blockedApps == 0 && blockedWebsites == 0) {
                    Toast.makeText(
                        requireContext(),
                        "Please add apps or websites to block first",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                sessionManager.startBlockingSession(days, blockedApps, blockedWebsites)
                
                Toast.makeText(
                    requireContext(),
                    "Blocking session started for $days days!",
                    Toast.LENGTH_LONG
                ).show()

                parentFragmentManager.popBackStack()
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Error: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
