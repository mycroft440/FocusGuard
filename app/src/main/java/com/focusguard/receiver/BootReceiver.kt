package com.focusguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.focusguard.manager.BlockingSessionManager
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            Log.d("FocusGuardBoot", "Dispositivo foi reiniciado. Acordando o FocusGuard...")
            
            // Reativa sessões programadas/vivas
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    val sessionManager = BlockingSessionManager.getInstance(context)
                    val isActive = sessionManager.isBlockingActive()
                    Log.d("FocusGuardBoot", "Estado da sessão durante Boot: Active=\$isActive")
                } catch (e: Exception) {
                    Log.e("FocusGuardBoot", "Falha ao reagendar FocusGuard após o Boot", e)
                }
            }
        }
    }
}
