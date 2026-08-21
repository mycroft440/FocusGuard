package com.focusguard.platform

import android.content.Context
import android.widget.Toast
import com.focusguard.R
import com.focusguard.domain.port.BlockingRuntimePort
import com.focusguard.domain.port.BlockingSnapshot
import com.focusguard.domain.port.BlockingUserMessage
import com.focusguard.receiver.BlockingScheduleReceiver
import com.focusguard.service.BlockingAccessibilityService
import com.focusguard.service.PomodoroForegroundService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class AndroidBlockingRuntimeAdapter @Inject constructor(
    @ApplicationContext private val context: Context
) : BlockingRuntimePort {

    override suspend fun showUserMessage(message: BlockingUserMessage) {
        val (resourceId, duration) = when (message) {
            BlockingUserMessage.POMODORO_STARTED ->
                R.string.modo_pomodoro_ativado_foco_total to Toast.LENGTH_LONG
            BlockingUserMessage.PASSWORD_SESSIONS_ENDED ->
                R.string.bloqueios_por_senha_encerrados to Toast.LENGTH_SHORT
        }
        withContext(Dispatchers.Main) {
            Toast.makeText(context, context.getString(resourceId), duration).show()
        }
    }

    override fun stopPomodoroForeground() {
        PomodoroForegroundService.stop(context)
    }

    override fun scheduleReconciliation(atMillis: Long?) {
        BlockingScheduleReceiver.scheduleAt(context, atMillis)
    }

    override fun publishSnapshot(snapshot: BlockingSnapshot) {
        context.sendBroadcast(
            BlockingAccessibilityService.createRefreshBlockingIntent(
                context = context,
                blockedApps = snapshot.blockedApps,
                blockedSites = snapshot.blockedSites,
                blockingActive = snapshot.blockingActive,
                strictPomodoro = snapshot.strictPomodoro
            )
        )
    }
}
