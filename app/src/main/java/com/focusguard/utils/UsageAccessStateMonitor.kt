package com.focusguard.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.focusguard.R
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.database.AppDatabase
import com.focusguard.security.UsageAccessPausePolicy
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Observes Usage Access while the FocusGuard process is alive.
 *
 * Counterpart to [AccessibilityStateMonitor], which existed for Accessibility but
 * had no equivalent here — so revoking Usage Access disabled every app usage limit
 * with no warning at all. See [UsageAccessPausePolicy] for why.
 *
 * Unlike Accessibility there is no system broadcast for this permission changing,
 * so polling is the only option. The check is a cheap AppOps lookup.
 */
object UsageAccessStateMonitor {

    private const val TAG = "UsageAccessMonitor"
    private const val POLL_INTERVAL_MS = 30_000L

    /** Shared with [AccessibilityStateMonitor]: both report protection status. */
    private const val CHANNEL_ID = "focusguard_permission_status"
    private const val NOTIFICATION_ID = 9002

    private val handler = Handler(Looper.getMainLooper())
    private val checkInFlight = AtomicBoolean(false)

    @Volatile
    private var monitorJob: Job? = null

    @Volatile
    private var lifecycleGeneration = 0L

    private var pollingRunnable: Runnable? = null

    @Volatile
    private var lastKnownState: UsageAccessPausePolicy.State? = null

    /**
     * Starts one restartable monitor generation.
     *
     * Every generation owns its own [SupervisorJob]. This is deliberately not a
     * process-global permanent scope: Robolectric tears Android sandboxes down
     * between tests, and a late AppOps/Room access from an old generation can
     * otherwise initialize framework services while the previous sandbox is
     * already being reset.
     */
    @Synchronized
    fun start(context: Context) {
        if (monitorJob?.isActive == true) return

        val appContext = context.applicationContext
        val generation = lifecycleGeneration + 1
        lifecycleGeneration = generation
        lastKnownState = null
        checkInFlight.set(false)

        val generationJob = SupervisorJob()
        monitorJob = generationJob
        val generationScope = CoroutineScope(generationJob + Dispatchers.IO)

        val runnable = object : Runnable {
            override fun run() {
                if (!isGenerationActive(generation)) return
                checkAndHandle(appContext, generationScope, generation)
                if (isGenerationActive(generation)) {
                    handler.postDelayed(this, POLL_INTERVAL_MS)
                }
            }
        }
        pollingRunnable = runnable

        checkAndHandle(appContext, generationScope, generation)
        handler.postDelayed(runnable, POLL_INTERVAL_MS)
    }

    /**
     * Stops the current generation synchronously.
     *
     * Cancellation alone is insufficient because the permission/AppOps lookup is
     * synchronous. Waiting for the generation job guarantees that no check from a
     * stopped monitor can escape into the next lifecycle/test sandbox. A later
     * [start] creates a fresh SupervisorJob and can run normally.
     */
    @Synchronized
    fun stop() {
        lifecycleGeneration += 1
        pollingRunnable?.let(handler::removeCallbacks)
        pollingRunnable = null

        val jobToStop = monitorJob
        monitorJob = null
        jobToStop?.cancel()
        if (jobToStop != null) {
            runBlocking {
                jobToStop.join()
            }
        }

        checkInFlight.set(false)
        lastKnownState = null
    }

    private fun isGenerationActive(generation: Long): Boolean =
        lifecycleGeneration == generation && monitorJob?.isActive == true

    private fun checkAndHandle(
        context: Context,
        scope: CoroutineScope,
        generation: Long
    ) {
        if (!isGenerationActive(generation)) return
        if (!checkInFlight.compareAndSet(false, true)) return

        scope.launch {
            try {
                currentCoroutineContext().ensureActive()
                if (!isGenerationActive(generation)) return@launch
                val granted = PermissionUtils.isUsageAccessEnabled(context)
                currentCoroutineContext().ensureActive()
                if (!isGenerationActive(generation)) return@launch

                // Counted in the database rather than trusted from memory: the limit
                // list changes from several screens and a stale count would either
                // warn about nothing or stay silent when it matters.
                val enabledAppLimits = runCatching {
                    AppDatabase.getDatabase(context)
                        .appUsageLimitDao()
                        .getAllActiveLimitsStatic()
                        .size
                }.getOrElse { error ->
                    if (isGenerationActive(generation)) {
                        FocusGuardLogger.logError(
                            TAG,
                            "Falha ao contar limites ativos",
                            error
                        )
                    }
                    return@launch
                }

                currentCoroutineContext().ensureActive()
                if (!isGenerationActive(generation)) return@launch

                val state = UsageAccessPausePolicy.evaluate(
                    usageAccessGranted = granted,
                    enabledAppLimitCount = enabledAppLimits
                )
                if (state == lastKnownState) return@launch
                lastKnownState = state

                currentCoroutineContext().ensureActive()
                if (!isGenerationActive(generation)) return@launch

                if (UsageAccessPausePolicy.shouldWarn(state)) {
                    FocusGuardLogger.log(
                        TAG,
                        "Acesso de uso revogado com $enabledAppLimits limite(s) ativo(s); " +
                            "limites de app pararam de ser aplicados"
                    )
                    handler.post {
                        if (isGenerationActive(generation)) {
                            sendPausedNotification(context)
                        }
                    }
                } else {
                    handler.post {
                        if (isGenerationActive(generation)) {
                            cancelPausedNotification(context)
                        }
                    }
                }
            } finally {
                checkInFlight.set(false)
            }
        }
    }

    private fun sendPausedNotification(context: Context) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.accessibility_paused_channel),
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        description = context.getString(
                            R.string.accessibility_paused_channel_description
                        )
                    }
                )
            }

            val deviceOwnerActive = DeviceOwnerManager.getInstance(context).isDeviceOwnerActive()
            val settingsIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pendingIntent = PendingIntent.getActivity(
                context,
                1,
                settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val message = context.getString(
                if (deviceOwnerActive) {
                    R.string.usage_access_paused_device_owner_message
                } else {
                    R.string.usage_access_paused_message
                }
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_warning)
                .setContentTitle(context.getString(R.string.usage_access_paused_title))
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(
                    if (deviceOwnerActive) {
                        NotificationCompat.PRIORITY_HIGH
                    } else {
                        NotificationCompat.PRIORITY_DEFAULT
                    }
                )
                .setContentIntent(pendingIntent)
                // Dismissible on consumer installs, persistent under Device Owner —
                // same rule as the Accessibility warning.
                .setAutoCancel(!deviceOwnerActive)
                .setOngoing(deviceOwnerActive)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        } catch (error: RuntimeException) {
            FocusGuardLogger.logError(TAG, "Falha ao enviar aviso de acesso de uso", error)
        }
    }

    private fun cancelPausedNotification(context: Context) {
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.cancel(NOTIFICATION_ID)
        }
    }
}
