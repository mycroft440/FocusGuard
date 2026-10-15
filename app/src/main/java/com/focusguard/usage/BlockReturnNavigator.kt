package com.focusguard.usage

import android.app.Activity
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.focusguard.MainActivity
import com.focusguard.utils.FocusGuardLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Para onde o Voltar das telas de bloqueio leva: a tela que estava na frente logo
 * antes da tentativa de entrar no app bloqueado.
 */
internal object BlockReturnPolicy {
    /** Eventos de uso um pouco depois do aviso de acessibilidade ainda são a entrada. */
    const val ENTRY_TOLERANCE_MILLIS = 3_000L

    /**
     * Voltar ao mesmo app pela mesma tentativa logo de novo é sinal de que o app de
     * origem reabre o app bloqueado (um link aberto na tarefa dele): a tela inicial
     * quebra esse vaivém.
     */
    const val LOOP_GUARD_MILLIS = 15_000L

    data class Resumed(
        val packageName: String,
        val className: String?,
        val timeMs: Long
    )

    sealed interface Destination {
        data object Home : Destination
        data object FocusGuard : Destination
        data class App(val packageName: String) : Destination
    }

    data class LastReturn(
        val blockedPackage: String,
        val destination: Destination,
        val atMs: Long
    )

    /**
     * Lê as Activities retomadas em ordem e devolve o que estava na frente quando
     * [blockedPackage] entrou. As telas de bloqueio do próprio app e as telas de
     * sistema (compartilhar, permissões) não contam: não são uma tela para onde voltar.
     */
    fun originBefore(
        resumedActivities: List<Resumed>,
        blockedPackage: String,
        attemptAtMs: Long,
        ownPackage: String,
        ownMainActivity: String,
        isHomeScreen: (String) -> Boolean,
        isLaunchable: (String) -> Boolean
    ): Destination {
        var lastCandidate: Destination? = null
        var originAtEntry: Destination? = null
        for (resumed in resumedActivities) {
            if (resumed.timeMs > attemptAtMs + ENTRY_TOLERANCE_MILLIS) break
            when {
                resumed.packageName.isBlank() -> Unit
                resumed.packageName == blockedPackage -> originAtEntry = lastCandidate
                resumed.packageName == ownPackage -> {
                    if (resumed.className == ownMainActivity) {
                        lastCandidate = Destination.FocusGuard
                    }
                }
                isHomeScreen(resumed.packageName) -> lastCandidate = Destination.Home
                isLaunchable(resumed.packageName) ->
                    lastCandidate = Destination.App(resumed.packageName)
            }
        }
        return originAtEntry ?: lastCandidate ?: Destination.Home
    }

    fun applyLoopGuard(
        destination: Destination,
        blockedPackage: String,
        lastReturn: LastReturn?,
        nowMs: Long
    ): Destination {
        if (destination !is Destination.App || lastReturn == null) return destination
        val repeated = lastReturn.blockedPackage == blockedPackage &&
            lastReturn.destination == destination &&
            nowMs - lastReturn.atMs in 0..LOOP_GUARD_MILLIS
        return if (repeated) Destination.Home else destination
    }
}

/** Parte Android do Voltar: lê o histórico de uso e abre a tela de antes. */
internal object BlockReturnNavigator {
    private const val LOOKBACK_MILLIS = 2L * 60L * 60L * 1_000L

    @Volatile private var lastReturn: BlockReturnPolicy.LastReturn? = null

    suspend fun resolve(
        context: Context,
        blockedPackage: String,
        attemptAtMs: Long
    ): BlockReturnPolicy.Destination = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val destination = runCatching {
            readOrigin(appContext, blockedPackage, attemptAtMs)
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "BlockReturn",
                "Falha ao achar a tela anterior a $blockedPackage",
                error
            )
        }.getOrDefault(BlockReturnPolicy.Destination.Home)

        BlockReturnPolicy.applyLoopGuard(
            destination = destination,
            blockedPackage = blockedPackage,
            lastReturn = lastReturn,
            nowMs = System.currentTimeMillis()
        )
    }

    /** Abre [destination]; se ela não abrir, vai para a tela inicial. */
    fun navigate(
        activity: Activity,
        blockedPackage: String,
        destination: BlockReturnPolicy.Destination
    ) {
        val intent = when (destination) {
            BlockReturnPolicy.Destination.Home -> null
            BlockReturnPolicy.Destination.FocusGuard ->
                Intent(activity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            is BlockReturnPolicy.Destination.App -> runCatching {
                activity.packageManager.getLaunchIntentForPackage(destination.packageName)
            }.getOrNull()?.addFlags(
                // Como o ícone na tela inicial: traz a tarefa do app de volta como estava.
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
        }
        val opened = intent != null && runCatching { activity.startActivity(intent) }
            .onFailure { error ->
                FocusGuardLogger.logError(
                    "BlockReturn",
                    "Falha ao voltar para $destination",
                    error
                )
            }
            .isSuccess
        if (!opened) runCatching { activity.startActivity(homeIntent()) }
        lastReturn = BlockReturnPolicy.LastReturn(
            blockedPackage = blockedPackage,
            destination = if (opened) destination else BlockReturnPolicy.Destination.Home,
            atMs = System.currentTimeMillis()
        )
    }

    fun homeIntent(): Intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_HOME)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }

    private fun readOrigin(
        context: Context,
        blockedPackage: String,
        attemptAtMs: Long
    ): BlockReturnPolicy.Destination {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return BlockReturnPolicy.Destination.Home
        val packageManager = context.packageManager
        val queryEnd = attemptAtMs + BlockReturnPolicy.ENTRY_TOLERANCE_MILLIS
        val events = manager.queryEvents(
            (attemptAtMs - LOOKBACK_MILLIS).coerceAtLeast(0L),
            queryEnd
        ) ?: return BlockReturnPolicy.Destination.Home

        val resumed = mutableListOf<BlockReturnPolicy.Resumed>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                resumed += BlockReturnPolicy.Resumed(
                    packageName = event.packageName.orEmpty(),
                    className = event.className,
                    timeMs = event.timeStamp
                )
            }
        }

        val launchable = mutableMapOf<String, Boolean>()
        val homePackages = homeScreenPackages(packageManager)
        return BlockReturnPolicy.originBefore(
            resumedActivities = resumed,
            blockedPackage = blockedPackage,
            attemptAtMs = attemptAtMs,
            ownPackage = context.packageName,
            ownMainActivity = MainActivity::class.java.name,
            isHomeScreen = { it in homePackages },
            isLaunchable = { packageName ->
                launchable.getOrPut(packageName) {
                    runCatching {
                        packageManager.getLaunchIntentForPackage(packageName) != null
                    }.getOrDefault(false)
                }
            }
        )
    }

    /** Todo app de tela inicial instalado (e não só o padrão, que pode não estar definido). */
    private fun homeScreenPackages(packageManager: PackageManager): Set<String> = runCatching {
        packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            0
        ).mapNotNullTo(mutableSetOf()) { it.activityInfo?.packageName }
    }.getOrDefault(emptySet())
}
