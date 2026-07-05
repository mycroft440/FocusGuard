package com.focusguard.utils

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.focusguard.utils.FocusGuardLogger

/**
 * Monitor que detecta quando o BlockingAccessibilityService é desativado
 * por fora da UI interceptada (ex: via `adb shell settings put secure
 * enabled_accessibility_services ""`, ou via App Info → Force Stop).
 *
 * Estratégia híbrida de detecção:
 *
 * 1. **BroadcastReceiver** para `AccessibilityManager.ACTION_ACCESSIBILITY_SERVICE_STATE_CHANGED`
 *    — disparado pelo sistema quando o usuário ativa/desativa qualquer
 *    serviço de acessibilidade. Reação instantânea (~0ms).
 *
 * 2. **Polling periódico** via `AccessibilityManager.getEnabledAccessibilityServiceList()`
 *    — fallback caso o broadcast não dispare (alguns OEM ROMs suprimem).
 *    Roda a cada 30s quando o app está em foreground ou o Foreground Service
 *    está ativo.
 *
 * Quando detecta desativação:
 * - Loga o evento em FocusGuardLogs (para auditoria)
 * - Lança `AccessibilityDisabledActivity` (tela cheia de bloqueio)
 *   que exige que o usuário reative o serviço para usar o celular
 */
object AccessibilityStateMonitor {

    private const val TAG = "A11yStateMonitor"
    private const val POLL_INTERVAL_MS = 30_000L // 30 segundos

    private val handler = Handler(Looper.getMainLooper())
    private var isPolling = false
    private var lastKnownEnabled = false

    /**
     * Nome esperado do serviço de acessibilidade do FocusGuard.
     * Deve bater com o declarado em AndroidManifest.xml.
     */
    private const val EXPECTED_SERVICE_FLAT =
        "com.focusguard.v2/com.focusguard.service.BlockingAccessibilityService"

    /**
     * BroadcastReceiver para mudanças de estado de accessibility.
     * Disparado pelo sistema em qualquer toggle de accessibility.
     */
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (context == null) return
            if (intent?.action == AccessibilityManager.ACTION_ACCESSIBILITY_SERVICE_STATE_CHANGED) {
                FocusGuardLogger.log(TAG, "STATE_CHANGED broadcast recebido — verificando serviço")
                checkAndHandle(context)
            }
        }
    }

    /**
     * Inicia o monitor. Deve ser chamado no `onCreate` do FocusGuardApplication.
     *
     * Registra o BroadcastReceiver e (se o serviço estava ativo antes)
     * inicia o polling periódico.
     */
    fun start(context: Context) {
        val appContext = context.applicationContext
        lastKnownEnabled = isAccessibilityServiceEnabled(appContext)

        // Registra receiver para STATE_CHANGED
        val filter = IntentFilter(AccessibilityManager.ACTION_ACCESSIBILITY_SERVICE_STATE_CHANGED)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(stateReceiver, filter)
            }
            FocusGuardLogger.log(TAG, "Monitor iniciado. Estado inicial: $lastKnownEnabled")
        } catch (e: Throwable) {
            FocusGuardLogger.logError(TAG, "Falha ao registrar stateReceiver", e)
        }

        startPolling(appContext)
    }

    /**
     * Para o monitor. Deve ser chamado no `onTerminate` do Application
     * (embora na prática o Application nunca tenha onTerminate chamado
     * em devices reais — usado apenas para testes).
     */
    fun stop(context: Context) {
        try {
            context.applicationContext.unregisterReceiver(stateReceiver)
        } catch (_: Throwable) {
            // Receiver não registrado — ignore
        }
        stopPolling()
    }

    private fun startPolling(context: Context) {
        if (isPolling) return
        isPolling = true
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!isPolling) return
                try {
                    checkAndHandle(context.applicationContext)
                } catch (e: Throwable) {
                    FocusGuardLogger.logError(TAG, "Erro no poll", e)
                }
                if (isPolling) {
                    handler.postDelayed(this, POLL_INTERVAL_MS)
                }
            }
        }, POLL_INTERVAL_MS)
    }

    private fun stopPolling() {
        isPolling = false
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * Verifica se o serviço está ativo agora. Se estava ativo antes e foi
     * desativado, dispara o handler de desativação.
     */
    private fun checkAndHandle(context: Context) {
        val currentlyEnabled = isAccessibilityServiceEnabled(context)

        if (lastKnownEnabled && !currentlyEnabled) {
            // Serviço foi desativado enquanto o app rodava — bypass detectado!
            FocusGuardLogger.logError(
                TAG,
                "ALERTA: BlockingAccessibilityService foi DESATIVADO enquanto o app rodava. " +
                    "Possível bypass via adb ou App Info → Force Stop.",
                null
            )
            onAccessibilityDisabled(context)
        }

        lastKnownEnabled = currentlyEnabled
    }

    /**
     * Verifica via AccessibilityManager se o serviço do FocusGuard está
     * na lista de serviços habilitados.
     *
     * Mais robusto que `PermissionUtils.isAccessibilityServiceEnabled` porque
     * não depende de parsing de String do Settings.Secure — usa API nativa
     * do AccessibilityManager.
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                as? AccessibilityManager ?: return false
            val enabledServices = am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_GENERIC
            ) ?: return false
            enabledServices.any { it.resolveInfo.serviceInfo.packageName == context.packageName }
        } catch (e: Throwable) {
            FocusGuardLogger.logError(TAG, "Falha ao checar accessibility service", e)
            // Em caso de erro, assume que ainda está ativo para evitar
            // falsos positivos que bloqueariam o usuário indevidamente.
            true
        }
    }

    /**
     * Chamado quando o serviço é detectado como desativado.
     *
     * Lança a Activity de bloqueio em tela cheia para forçar o usuário
     * a reativar. Não pode ser silenciado — só fecha quando o serviço
     * volta a ficar ativo.
     */
    private fun onAccessibilityDisabled(context: Context) {
        try {
            val intent = Intent(context, com.focusguard.ui.AccessibilityDisabledActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_SHOW_WHEN_LOCKED or
                    Intent.FLAG_ACTIVITY_TURN_SCREEN_ON
                )
            }
            context.startActivity(intent)
        } catch (e: Throwable) {
            FocusGuardLogger.logError(TAG, "Falha ao lançar AccessibilityDisabledActivity", e)
            // Fallback: enviar notificação crítica se a activity não puder ser lançada
            sendCriticalNotification(context)
        }
    }

    private fun sendCriticalNotification(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as? android.app.NotificationManager ?: return
            val channelId = "focusguard_critical"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    channelId,
                    "Alertas Críticos",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Avisos de bypass de proteção"
                    enableVibration(true)
                }
                nm.createNotificationChannel(channel)
            }

            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pendingIntent = android.app.PendingIntent.getActivity(
                context, 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val notification = androidx.core.app.NotificationCompat.Builder(context, channelId)
                .setSmallIcon(com.focusguard.R.drawable.ic_warning)
                .setContentTitle("🚨 Proteção desativada!")
                .setContentText("Toque para reativar o FocusGuard")
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
                .setCategory(android.app.Notification.CATEGORY_ERROR)
                .setContentIntent(pendingIntent)
                .setAutoCancel(false)
                .setOngoing(true)
                .build()

            nm.notify(9001, notification)
        } catch (e: Throwable) {
            FocusGuardLogger.logError(TAG, "Falha ao enviar notificação crítica", e)
        }
    }
}
