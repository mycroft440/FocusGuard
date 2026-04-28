package com.focusguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.utils.FocusGuardLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SessionExpirationReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.focusguard.ACTION_SESSION_EXPIRED") {
            val sessionId = intent.getIntExtra("SESSION_ID", -1)
            FocusGuardLogger.log("ExpirationReceiver", "Alarme recebido para expiração da sessão: $sessionId")
            
            val pendingResult = goAsync()
            // Ativamos o gatilho de limpeza no SessionManager
            scope.launch {
                try {
                    val sessionManager = BlockingSessionManager.getInstance(context)
                    // getActiveSessions já contém a lógica de detectar expirações e chamar checkAndEnforce
                    sessionManager.getActiveSessions()
                    FocusGuardLogger.log("ExpirationReceiver", "Limpeza de estado executada para sessão $sessionId")
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
