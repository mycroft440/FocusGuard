package com.focusguard.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import android.app.TimePickerDialog
import java.util.Calendar
import com.focusguard.R
import com.focusguard.database.AppDatabase
import com.focusguard.manager.BlockingSessionManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StartBlockingSessionFragment : BottomSheetDialogFragment() {

    private lateinit var btnStartTime: Button
    private lateinit var btnEndTime: Button
    private lateinit var chkRecurring: CheckBox
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

    // Variables to store selected times
    private var startHour = -1
    private var startMinute = -1
    private var endHour = -1
    private var endMinute = -1

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        database = AppDatabase.getDatabase(requireContext())
        sessionManager = BlockingSessionManager(requireContext())

        btnStartTime = view.findViewById(R.id.btnStartTime)
        btnEndTime = view.findViewById(R.id.btnEndTime)
        chkRecurring = view.findViewById(R.id.chkRecurring)
        startButton = view.findViewById(R.id.startButton)
        cancelButton = view.findViewById(R.id.cancelButton)

        btnStartTime.setOnClickListener {
            val calendar = Calendar.getInstance()
            val currentHour = if(startHour != -1) startHour else calendar.get(Calendar.HOUR_OF_DAY)
            val currentMinute = if(startMinute != -1) startMinute else calendar.get(Calendar.MINUTE)

            TimePickerDialog(requireContext(), { _, selectedHour, selectedMinute ->
                startHour = selectedHour
                startMinute = selectedMinute
                btnStartTime.text = String.format("Start: %02d:%02d", startHour, startMinute)
            }, currentHour, currentMinute, true).show()
        }

        btnEndTime.setOnClickListener {
            val calendar = Calendar.getInstance()
            val currentHour = if(endHour != -1) endHour else calendar.get(Calendar.HOUR_OF_DAY)
            val currentMinute = if(endMinute != -1) endMinute else calendar.get(Calendar.MINUTE)

            TimePickerDialog(requireContext(), { _, selectedHour, selectedMinute ->
                endHour = selectedHour
                endMinute = selectedMinute
                btnEndTime.text = String.format("End: %02d:%02d", endHour, endMinute)
            }, currentHour, currentMinute, true).show()
        }

        startButton.setOnClickListener {
            startBlockingSession()
        }

        cancelButton.setOnClickListener {
            dismiss()
        }
    }

    private fun startBlockingSession() {
        scope.launch {
            try {
                if (startHour == -1 || endHour == -1) {
                    Toast.makeText(requireContext(), "Please select both start and end times", Toast.LENGTH_SHORT).show()
                    return@launch
                }
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

                val isRecurring = chkRecurring.isChecked
                sessionManager.startBlockingSession(
                    startHour = startHour,
                    startMinute = startMinute,
                    endHour = endHour,
                    endMinute = endMinute,
                    isRecurring = isRecurring,
                    blockedAppsCount = blockedApps,
                    blockedWebsitesCount = blockedWebsites
                )
                
                dismiss()
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
