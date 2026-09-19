package com.focusguard.accessibility.website.diagnostics

import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.focusguard.BuildConfig
import com.focusguard.utils.WebsiteBlocker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal data class WebsiteBlockingDiagnosticReport(
    val fileName: String,
    val modifiedAtMillis: Long,
    val sizeBytes: Long
)

internal data class WebsiteBlockingDiagnosticsClearResult(
    val deletedCount: Int,
    val failedCount: Int
)

/**
 * Dedicated, best-effort diagnostics for website protection.
 *
 * This storage is intentionally independent from FocusGuardLogger. Calls on the
 * accessibility path only mutate small in-memory records; report I/O happens on
 * Dispatchers.IO and must never change protection behavior.
 */
internal object WebsiteBlockingDiagnostics {
    private const val DIRECTORY_NAME = "WebsiteBlockingDiagnostics"
    private const val REPORT_PREFIX = "website_block_failure_"
    private const val REPORT_SUFFIX = ".txt"
    private const val MAX_REPORTS = 60
    private const val RETENTION_MILLIS = 14L * 24L * 60L * 60L * 1000L
    private const val IDENTIFICATION_DEDUPE_MILLIS = 2_000L
    private const val IDENTIFICATION_DEDUPE_PRUNE_MILLIS = 60_000L
    private const val MAX_IDENTIFICATION_DEDUPE_ENTRIES = 128

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transitions = ConcurrentHashMap<String, TransitionState>()
    private val recentIdentificationFailures = ConcurrentHashMap<String, Long>()
    private val reportSequence = AtomicLong(0L)
    private val reportFileLock = Any()

    @Volatile private var appContext: Context? = null

    private data class TransitionState(
        val browserPackageName: String,
        val transitionId: Long,
        val startedAtMillis: Long,
        val expectedWindowId: Int,
        val inspectionGeneration: Long,
        val blockedTarget: String?,
        val matchedRule: String?,
        val strictDestination: Boolean,
        @Volatile var curtainGeneration: Long = 0L,
        @Volatile var submitAcceptedCount: Int = 0,
        @Volatile var safeRedirectConfirmed: Boolean = false,
        @Volatile var destinationRequested: Boolean = false,
        @Volatile var destinationConfirmed: Boolean = false,
        @Volatile var latestObservedEventUptimeMillis: Long = 0L,
        @Volatile var latestWindowTransitionEventUptimeMillis: Long = 0L,
        @Volatile var latestNavigationEvidenceEventUptimeMillis: Long = 0L,
        @Volatile var reboundWindowId: Int? = null
    )

    @Synchronized
    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        scope.launch { cleanOldReports() }
    }

    fun beginTransition(
        browserPackageName: String,
        transitionId: Long,
        expectedWindowId: Int,
        inspectionGeneration: Long,
        blockedCandidate: String?,
        blockedRules: Set<String>,
        strictDestination: Boolean
    ) {
        if (browserPackageName.isBlank() || transitionId <= 0L) return
        val safeTarget = WebsiteBlockingDiagnosticPolicy.sanitizeTarget(blockedCandidate)

        // Diagnostics must be observational. The regular matcher can update PASSWORD
        // grant lifecycle state, so use the explicitly side-effect-free matching path.
        val matchedRule = blockedCandidate
            ?.let { WebsiteBlocker.findMatchingRulesIgnoringGrants(it, blockedRules).firstOrNull() }
            ?.let(WebsiteBlocker::displayRule)

        transitions[key(browserPackageName, transitionId)] = TransitionState(
            browserPackageName = browserPackageName,
            transitionId = transitionId,
            startedAtMillis = System.currentTimeMillis(),
            expectedWindowId = expectedWindowId,
            inspectionGeneration = inspectionGeneration,
            blockedTarget = safeTarget,
            matchedRule = matchedRule,
            strictDestination = strictDestination
        )
    }

    fun markCurtain(browserPackageName: String, transitionId: Long, generation: Long) {
        if (generation <= 0L) return
        transitions[key(browserPackageName, transitionId)]?.curtainGeneration = generation
    }

    /** markSanitizationRequested happens only after a submit action was accepted. */
    fun markSubmitAccepted(browserPackageName: String, transitionId: Long) {
        transitions[key(browserPackageName, transitionId)]?.let { state ->
            state.submitAcceptedCount += 1
        }
    }

    fun markRedirectConfirmed(browserPackageName: String, transitionId: Long) {
        transitions[key(browserPackageName, transitionId)]?.safeRedirectConfirmed = true
    }

    fun markDestinationRequested(browserPackageName: String, transitionId: Long) {
        transitions[key(browserPackageName, transitionId)]?.destinationRequested = true
    }

    fun markDestinationConfirmed(browserPackageName: String, transitionId: Long) {
        transitions[key(browserPackageName, transitionId)]?.destinationConfirmed = true
    }

    fun markWindowRebound(browserPackageName: String, transitionId: Long, windowId: Int) {
        transitions[key(browserPackageName, transitionId)]?.reboundWindowId = windowId
    }

    fun observeBrowserEvent(
        browserPackageName: String,
        transitionId: Long,
        eventUptimeMillis: Long,
        eventType: Int,
        navigationEvidence: Boolean
    ) {
        transitions[key(browserPackageName, transitionId)]?.let { state ->
            state.latestObservedEventUptimeMillis = maxOf(
                state.latestObservedEventUptimeMillis,
                eventUptimeMillis
            )
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
            ) {
                state.latestWindowTransitionEventUptimeMillis = maxOf(
                    state.latestWindowTransitionEventUptimeMillis,
                    eventUptimeMillis
                )
            }
            if (navigationEvidence) {
                state.latestNavigationEvidenceEventUptimeMillis = maxOf(
                    state.latestNavigationEvidenceEventUptimeMillis,
                    eventUptimeMillis
                )
            }
        }
    }

    fun finishTransition(browserPackageName: String, transitionId: Long) {
        val state = transitions.remove(key(browserPackageName, transitionId)) ?: return
        val evidence = WebsiteBlockingTransitionEvidence(
            curtainShown = state.curtainGeneration > 0L,
            submitAccepted = state.submitAcceptedCount > 0,
            navigationEvidenceObserved = state.latestNavigationEvidenceEventUptimeMillis > 0L,
            safeRedirectConfirmed = state.safeRedirectConfirmed,
            strictDestination = state.strictDestination,
            destinationRequested = state.destinationRequested,
            destinationConfirmed = state.destinationConfirmed
        )
        val diagnosis = WebsiteBlockingDiagnosticPolicy.diagnoseTransition(evidence) ?: return
        writeFailureAsync(state, diagnosis)
    }

    fun discardTransition(browserPackageName: String, transitionId: Long) {
        transitions.remove(key(browserPackageName, transitionId))
    }

    fun recordIdentificationFailure(
        browserPackageName: String,
        windowId: Int,
        status: String,
        addressBarObservable: Boolean,
        webContentObserved: Boolean,
        evidence: Collection<String>
    ) {
        val now = System.currentTimeMillis()
        pruneIdentificationDedupe(now)
        val dedupeKey = "$browserPackageName:$windowId:$status"
        val previous = recentIdentificationFailures.put(dedupeKey, now)
        if (previous != null && now - previous < IDENTIFICATION_DEDUPE_MILLIS) return

        val diagnosis = WebsiteBlockingFailureDiagnosis(
            stage = WebsiteBlockingFailureStage.IDENTIFICATION,
            summary = "A identificação do endereço foi esgotada sem URL utilizável.",
            conclusion = "O navegador exigia observação para aplicar a proteção de sites, mas a " +
                "recuperação terminou sem confirmar um endereço utilizável. A causa exata não " +
                "pôde ser confirmada com as evidências disponíveis."
        )
        val state = TransitionState(
            browserPackageName = browserPackageName,
            transitionId = 0L,
            startedAtMillis = now,
            expectedWindowId = windowId,
            inspectionGeneration = 0L,
            blockedTarget = null,
            matchedRule = null,
            strictDestination = false
        )
        writeFailureAsync(
            state = state,
            diagnosis = diagnosis,
            extraLines = listOf(
                "Status da identificação: $status",
                "Barra de endereço observável: ${yesNo(addressBarObservable)}",
                "Conteúdo web observado: ${yesNo(webContentObserved)}",
                "Evidências da identificação: ${evidence.ifEmpty { listOf("nenhuma") }.joinToString(", ")}"
            )
        )
    }

    fun listReports(): List<WebsiteBlockingDiagnosticReport> = synchronized(reportFileLock) {
        val directory = reportDirectory() ?: return@synchronized emptyList()
        directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter(::isReportFile)
            .sortedByDescending(File::lastModified)
            .map { file ->
                WebsiteBlockingDiagnosticReport(
                    fileName = file.name,
                    modifiedAtMillis = file.lastModified(),
                    sizeBytes = file.length()
                )
            }
            .toList()
    }

    fun readReport(fileName: String): String? = synchronized(reportFileLock) {
        if (!validReportName(fileName)) return@synchronized null
        val directory = reportDirectory() ?: return@synchronized null
        val file = File(directory, fileName)
        runCatching {
            if (!file.isFile || file.parentFile?.canonicalFile != directory.canonicalFile) null
            else file.readText(Charsets.UTF_8)
        }.getOrNull()
    }

    /** Deletes only website diagnostic TXT files; active protection state is untouched. */
    fun clearReports(): WebsiteBlockingDiagnosticsClearResult = synchronized(reportFileLock) {
        val directory = reportDirectory()
            ?: return@synchronized WebsiteBlockingDiagnosticsClearResult(0, 0)
        if (!directory.exists()) {
            return@synchronized WebsiteBlockingDiagnosticsClearResult(0, 0)
        }

        var deleted = 0
        var failed = 0
        directory.listFiles().orEmpty().filter(::isReportFile).forEach { file ->
            if (runCatching { file.delete() }.getOrDefault(false)) deleted++ else failed++
        }
        WebsiteBlockingDiagnosticsClearResult(deletedCount = deleted, failedCount = failed)
    }

    private fun writeFailureAsync(
        state: TransitionState,
        diagnosis: WebsiteBlockingFailureDiagnosis,
        extraLines: List<String> = emptyList()
    ) {
        val context = appContext ?: return
        scope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                val packageInfo = runCatching {
                    context.packageManager.getPackageInfo(state.browserPackageName, 0)
                }.getOrNull()
                val versionName = packageInfo?.versionName ?: "não disponível"
                val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo?.longVersionCode?.toString()
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo?.versionCode?.toString()
                } ?: "não disponível"
                val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.getDefault())
                val elapsed = (now - state.startedAtMillis).coerceAtLeast(0L)
                val navigationEvidenceObserved = state.latestNavigationEvidenceEventUptimeMillis > 0L
                val windowTransitionObserved = state.latestWindowTransitionEventUptimeMillis > 0L
                val report = buildString {
                    appendLine("FocusGuard — Diagnóstico de bloqueio de sites")
                    appendLine("Gerado: ${formatter.format(Date(now))}")
                    appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine()
                    appendLine("Navegador: ${state.browserPackageName}")
                    appendLine("Versão do navegador: $versionName ($versionCode)")
                    appendLine("Transação: ${if (state.transitionId > 0L) state.transitionId else "não iniciada"}")
                    appendLine("Janela inicial: ${state.expectedWindowId}")
                    appendLine("Geração de inspeção: ${state.inspectionGeneration}")
                    state.reboundWindowId?.let { appendLine("Janela confirmada após navegação: $it") }
                    appendLine("Alvo bloqueado sanitizado: ${state.blockedTarget ?: "não disponível"}")
                    appendLine("Regra correspondente: ${state.matchedRule ?: "não disponível"}")
                    appendLine("Tempo observado: ${elapsed} ms")
                    appendLine()
                    appendLine("Resultado: FALHA / NÃO CONFIRMADO")
                    appendLine("Etapa: ${WebsiteBlockingDiagnosticPolicy.stageLabel(diagnosis.stage)}")
                    appendLine("Resumo: ${diagnosis.summary}")
                    appendLine()
                    appendLine("Evidências da transação:")
                    appendLine("- Cortina exibida: ${yesNo(state.curtainGeneration > 0L)}")
                    appendLine("- Submissões aceitas: ${state.submitAcceptedCount}")
                    appendLine("- Evidência de navegação após envio: ${yesNo(navigationEvidenceObserved)}")
                    appendLine("- Transição de janela observada: ${yesNo(windowTransitionObserved)}")
                    appendLine("- Destino seguro confirmado: ${yesNo(state.safeRedirectConfirmed)}")
                    appendLine("- Destino rigoroso solicitado: ${yesNo(state.destinationRequested)}")
                    appendLine("- Destino rigoroso confirmado: ${yesNo(state.destinationConfirmed)}")
                    appendLine("- Último evento observado (uptime): ${state.latestObservedEventUptimeMillis}")
                    appendLine("- Última transição de janela (uptime): ${state.latestWindowTransitionEventUptimeMillis}")
                    appendLine("- Última evidência de navegação (uptime): ${state.latestNavigationEvidenceEventUptimeMillis}")
                    extraLines.forEach { appendLine("- $it") }
                    appendLine()
                    appendLine("Conclusão:")
                    appendLine(diagnosis.conclusion)
                    appendLine()
                    appendLine(
                        "Privacidade: este relatório não grava caminho, query string, fragmento, " +
                            "conteúdo da página, senhas ou texto digitado em formulários."
                    )
                }

                synchronized(reportFileLock) {
                    val directory = reportDirectory() ?: return@synchronized
                    if (!directory.exists() && !directory.mkdirs()) return@synchronized
                    val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US)
                        .format(Date(now))
                    val browser = state.browserPackageName
                        .replace(Regex("[^A-Za-z0-9._-]"), "_")
                        .take(80)
                    val transitionSuffix = if (state.transitionId > 0L) {
                        "_t${state.transitionId}"
                    } else {
                        ""
                    }
                    val uniqueSuffix = "_n${reportSequence.incrementAndGet()}"
                    File(
                        directory,
                        "$REPORT_PREFIX${stamp}_${browser}${transitionSuffix}${uniqueSuffix}$REPORT_SUFFIX"
                    ).writeText(report, Charsets.UTF_8)
                    cleanOldReportsLocked(directory, now)
                }
            }
        }
    }

    private fun cleanOldReports() = synchronized(reportFileLock) {
        val directory = reportDirectory() ?: return@synchronized
        cleanOldReportsLocked(directory, System.currentTimeMillis())
    }

    private fun cleanOldReportsLocked(directory: File, now: Long) {
        if (!directory.exists()) return
        val reports = directory.listFiles().orEmpty().filter(::isReportFile)
        reports.filter { now - it.lastModified() > RETENTION_MILLIS }.forEach { it.delete() }
        directory.listFiles().orEmpty()
            .filter(::isReportFile)
            .sortedByDescending(File::lastModified)
            .drop(MAX_REPORTS)
            .forEach { it.delete() }
    }

    private fun pruneIdentificationDedupe(now: Long) {
        if (recentIdentificationFailures.size <= MAX_IDENTIFICATION_DEDUPE_ENTRIES) return

        recentIdentificationFailures.forEach { (dedupeKey, timestamp) ->
            if (now - timestamp > IDENTIFICATION_DEDUPE_PRUNE_MILLIS) {
                recentIdentificationFailures.remove(dedupeKey, timestamp)
            }
        }

        val overflow = recentIdentificationFailures.size - MAX_IDENTIFICATION_DEDUPE_ENTRIES
        if (overflow > 0) {
            recentIdentificationFailures.entries
                .sortedBy { it.value }
                .take(overflow)
                .forEach { entry ->
                    recentIdentificationFailures.remove(entry.key, entry.value)
                }
        }
    }

    private fun reportDirectory(): File? =
        appContext?.filesDir?.let { File(it, DIRECTORY_NAME) }

    private fun isReportFile(file: File): Boolean =
        file.isFile && validReportName(file.name)

    private fun validReportName(fileName: String): Boolean =
        fileName.startsWith(REPORT_PREFIX) &&
            fileName.endsWith(REPORT_SUFFIX) &&
            '/' !in fileName && '\\' !in fileName && fileName != "." && fileName != ".."

    private fun key(browserPackageName: String, transitionId: Long): String =
        "$browserPackageName#$transitionId"

    private fun yesNo(value: Boolean): String = if (value) "sim" else "não"
}
