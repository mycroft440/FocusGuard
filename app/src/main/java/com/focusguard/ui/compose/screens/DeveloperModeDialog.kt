package com.focusguard.ui.compose.screens

import android.annotation.TargetApi
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.focusguard.BuildConfig
import com.focusguard.R
import com.focusguard.focusmode.FocusModeManager
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.PasswordAppUnlockStore
import com.focusguard.security.PasswordTargetAccessGrant
import com.focusguard.security.PermissionRevocationFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ALL_EXIT_RECORDS = 0
private const val MAX_TRACE_CHARS = 160_000
// Set to true to restore the destructive maintenance actions in Dev Mode.
private const val SHOW_DESTRUCTIVE_MAINTENANCE_ACTIONS = false

/**
 * Maintenance-only surface opened from Settings.
 *
 * Its destructive actions intentionally do not use the master credential. This is
 * the explicit developer escape hatch requested by the product; the normal Settings
 * actions retain their authentication boundary.
 */
@Composable
internal fun DeveloperModeDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var maintenanceWorking by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var anrReport by remember { mutableStateOf("") }
    var anrLoading by remember { mutableStateOf(true) }

    val saveAnrLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                        writer.write(anrReport)
                    } ?: error("Unable to open ANR report destination")
                }.isSuccess
            }
            statusMessage = context.getString(
                if (saved) R.string.dev_mode_anr_saved
                else R.string.dev_mode_anr_save_failed
            )
        }
    }

    suspend fun refreshAnrReport() {
        anrLoading = true
        anrReport = collectAnrReport(context)
        anrLoading = false
    }

    LaunchedEffect(Unit) {
        refreshAnrReport()
    }

    Dialog(
        onDismissRequest = {
            if (!maintenanceWorking) onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.dev_mode_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(
                        enabled = !maintenanceWorking,
                        onClick = onDismiss
                    ) {
                        Text(stringResource(R.string.dev_mode_close))
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.dev_mode_warning),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )

                if (SHOW_DESTRUCTIVE_MAINTENANCE_ACTIONS) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = stringResource(R.string.dev_mode_revoke_blocks),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.dev_mode_revoke_blocks_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !maintenanceWorking,
                        onClick = {
                            maintenanceWorking = true
                            statusMessage = null
                            scope.launch {
                                val removed = revokeAllBlocksForDeveloperMode(context)
                                statusMessage = context.getString(
                                    if (removed) R.string.master_remove_all_blocks_success
                                    else R.string.master_remove_all_blocks_failed
                                )
                                maintenanceWorking = false
                            }
                        }
                    ) {
                        Text(stringResource(R.string.dev_mode_revoke_blocks))
                    }

                    Spacer(Modifier.height(22.dp))
                    Text(
                        text = stringResource(R.string.dev_mode_revoke_permissions),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.dev_mode_revoke_permissions_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !maintenanceWorking,
                        onClick = {
                            maintenanceWorking = true
                            statusMessage = null
                            scope.launch {
                                val result = runCatching {
                                    PermissionRevocationFlow.revokeRequestedAccess(context)
                                }.getOrNull()
                                statusMessage = context.getString(
                                    when {
                                        result == null -> R.string.settings_revoke_permissions_incomplete
                                        !result.hadRequestedAccess ->
                                            R.string.settings_revoke_permissions_none_active
                                        result.allRequestedAccessRevoked ->
                                            R.string.settings_revoke_permissions_success
                                        else -> R.string.settings_revoke_permissions_incomplete
                                    }
                                )
                                maintenanceWorking = false
                            }
                        }
                    ) {
                        Text(stringResource(R.string.dev_mode_revoke_permissions))
                    }
                }

                if (maintenanceWorking) {
                    Spacer(Modifier.height(14.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }

                statusMessage?.let { message ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(Modifier.height(26.dp))
                HorizontalDivider()
                Spacer(Modifier.height(22.dp))

                Text(
                    text = stringResource(R.string.dev_mode_anr_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.dev_mode_anr_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                if (anrLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                } else {
                    SelectionContainer {
                        Text(
                            text = anrReport,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = !anrLoading && anrReport.isNotBlank(),
                        onClick = {
                            val clipboard = context.getSystemService(
                                Context.CLIPBOARD_SERVICE
                            ) as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("FocusGuard ANR", anrReport)
                            )
                            statusMessage = context.getString(R.string.dev_mode_anr_copied)
                        }
                    ) {
                        Text(stringResource(R.string.dev_mode_anr_copy))
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = !anrLoading && anrReport.isNotBlank(),
                        onClick = {
                            saveAnrLauncher.launch(
                                "focusguard-anr-${System.currentTimeMillis()}.txt"
                            )
                        }
                    ) {
                        Text(stringResource(R.string.dev_mode_anr_download))
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !anrLoading,
                    onClick = {
                        scope.launch { refreshAnrReport() }
                    }
                ) {
                    Text(stringResource(R.string.dev_mode_anr_refresh))
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private suspend fun revokeAllBlocksForDeveloperMode(context: Context): Boolean =
    withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val blockingManager = BlockingSessionManager.getInstance(appContext)
        val focusModeManager = FocusModeManager.getInstance(appContext)
        val targetCredentialStore = PasswordAppUnlockStore(appContext)

        try {
            focusModeManager.forceStopForDevelopmentExit()
            PasswordTargetAccessGrant.clear()
            val removed = blockingManager.removeAllBlocksForDevelopmentExit()
            if (removed) {
                targetCredentialStore.clearAll()
                blockingManager.checkAndEnforce()
            }
            removed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

private suspend fun collectAnrReport(context: Context): String = withContext(Dispatchers.IO) {
    val generatedAt = SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss Z",
        Locale.getDefault()
    ).format(Date())
    val header = buildString {
        appendLine("FocusGuard ANR diagnostic report")
        appendLine("Generated: $generatedAt")
        appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Package: ${context.packageName}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Source: Android ApplicationExitInfo historical process-exit buffer")
        appendLine(
            "Note: Android keeps this history and ANR traces in finite circular buffers; " +
                "older records or traces may have been overwritten."
        )
        appendLine()
    }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        return@withContext header + context.getString(R.string.dev_mode_anr_unavailable)
    }

    header + collectAnrEntriesApi30(context)
}

private data class TraceReadResult(
    val text: String? = null,
    val truncated: Boolean = false,
    val error: String? = null
)

private data class AnrReportEntry(
    val info: ApplicationExitInfo,
    val trace: TraceReadResult,
    val definitiveAnr: Boolean
)

@TargetApi(Build.VERSION_CODES.R)
private fun collectAnrEntriesApi30(context: Context): String {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val exitsResult = runCatching {
        activityManager.getHistoricalProcessExitReasons(
            context.packageName,
            0,
            ALL_EXIT_RECORDS
        )
    }

    val exits = exitsResult.getOrElse { error ->
        return buildString {
            appendLine("Collection status: ERROR")
            appendLine("Historical exits inspected: 0")
            appendLine("Error type: ${error.javaClass.simpleName}")
            appendLine("Error message: ${error.message ?: "No message supplied by Android"}")
            appendLine()
            appendLine(
                "This is a collection failure, not proof that the device has no ANR records."
            )
        }
    }

    val entries = buildList {
        exits.forEach { info ->
            val definitiveAnr = info.reason == ApplicationExitInfo.REASON_ANR
            val couldContainRecoveredAnrTrace =
                info.reason != ApplicationExitInfo.REASON_CRASH_NATIVE

            if (definitiveAnr) {
                add(
                    AnrReportEntry(
                        info = info,
                        trace = readAnrTrace(info),
                        definitiveAnr = true
                    )
                )
            } else if (couldContainRecoveredAnrTrace) {
                val trace = readAnrTrace(info)
                if (!trace.text.isNullOrBlank()) {
                    add(
                        AnrReportEntry(
                            info = info,
                            trace = trace,
                            definitiveAnr = false
                        )
                    )
                }
            }
        }
    }

    val definitiveCount = entries.count { it.definitiveAnr }
    val recoveredTraceCandidates = entries.size - definitiveCount

    if (entries.isEmpty()) {
        return buildString {
            appendLine("Collection status: OK")
            appendLine("Historical exits inspected: ${exits.size}")
            appendLine("Definitive ANRs: 0")
            appendLine("Recovered-ANR trace candidates: 0")
            appendLine()
            appendLine(context.getString(R.string.dev_mode_anr_empty))
        }
    }

    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.getDefault())
    return buildString {
        appendLine("Collection status: OK")
        appendLine("Historical exits inspected: ${exits.size}")
        appendLine("Definitive ANRs: $definitiveCount")
        appendLine("Recovered-ANR trace candidates: $recoveredTraceCandidates")
        if (recoveredTraceCandidates > 0) {
            appendLine(
                "Candidate note: these records contain a text trace compatible with a recovered " +
                    "ANR, but their final process-exit reason was not ANR."
            )
        }
        appendLine()

        entries.forEachIndexed { index, entry ->
            val info = entry.info
            val classification = if (entry.definitiveAnr) {
                "DEFINITIVE ANR"
            } else {
                "RECOVERED-ANR TRACE CANDIDATE"
            }

            appendLine("===== ANR ${index + 1} [$classification] =====")
            appendLine("Event key: ${info.timestamp}-${info.pid}-${info.reason}")
            appendLine("Timestamp: ${formatter.format(Date(info.timestamp))}")
            appendLine("Process: ${info.processName}")
            appendLine("PID: ${info.pid}")
            appendLine("Reason: ${exitReasonLabel(info.reason)} (${info.reason})")
            appendLine("Status: ${info.status}")
            appendLine(
                "Importance: ${importanceLabel(info.importance)} (${info.importance})"
            )
            appendLine("PSS: ${formatMemoryKb(info.pss)}")
            appendLine("RSS: ${formatMemoryKb(info.rss)}")
            info.description?.takeIf { it.isNotBlank() }?.let { description ->
                appendLine("Description: $description")
            }

            when {
                !entry.trace.text.isNullOrBlank() -> {
                    appendLine(
                        if (entry.trace.truncated) {
                            "Trace status: AVAILABLE (truncated at $MAX_TRACE_CHARS characters)"
                        } else {
                            "Trace status: AVAILABLE"
                        }
                    )
                    appendLine("Trace:")
                    appendLine(entry.trace.text)
                }

                entry.trace.error != null -> {
                    appendLine("Trace status: READ ERROR")
                    appendLine("Trace error: ${entry.trace.error}")
                }

                else -> {
                    appendLine(
                        "Trace status: UNAVAILABLE (Android may not have captured it or the " +
                            "circular trace buffer may have overwritten it)"
                    )
                }
            }
            appendLine()
        }
    }
}

@TargetApi(Build.VERSION_CODES.R)
private fun readAnrTrace(info: ApplicationExitInfo): TraceReadResult {
    val stream = try {
        info.traceInputStream
    } catch (error: Exception) {
        return TraceReadResult(error = describeError(error))
    } ?: return TraceReadResult()

    return try {
        stream.bufferedReader(Charsets.UTF_8).use { reader ->
            val output = StringBuilder()
            val buffer = CharArray(4_096)
            var truncated = false

            while (output.length < MAX_TRACE_CHARS) {
                val remaining = MAX_TRACE_CHARS - output.length
                val read = reader.read(buffer, 0, minOf(buffer.size, remaining))
                if (read < 0) break
                output.append(buffer, 0, read)
            }

            if (output.length >= MAX_TRACE_CHARS && reader.read() >= 0) {
                truncated = true
            }

            TraceReadResult(
                text = output.toString().trimEnd().takeIf { it.isNotBlank() },
                truncated = truncated
            )
        }
    } catch (error: Exception) {
        TraceReadResult(error = describeError(error))
    }
}

private fun describeError(error: Throwable): String = buildString {
    append(error.javaClass.simpleName)
    error.message?.takeIf { it.isNotBlank() }?.let { message ->
        append(": ")
        append(message)
    }
}

@TargetApi(Build.VERSION_CODES.R)
private fun exitReasonLabel(reason: Int): String = when (reason) {
    ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
    ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
    ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
    ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
    ApplicationExitInfo.REASON_CRASH -> "CRASH"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
    ApplicationExitInfo.REASON_ANR -> "ANR"
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
    ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
    ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
    ApplicationExitInfo.REASON_OTHER -> "OTHER"
    else -> "OTHER_OR_NEWER_ANDROID_REASON"
}

private fun importanceLabel(importance: Int): String = when (importance) {
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "FOREGROUND_SERVICE"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "PERCEPTIBLE"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING -> "TOP_SLEEPING"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_CANT_SAVE_STATE -> "CANT_SAVE_STATE"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "GONE"
    else -> "UNKNOWN"
}

private fun formatMemoryKb(valueKb: Long): String {
    if (valueKb <= 0L) return "$valueKb kB (not sampled or unavailable)"
    val mib = valueKb / 1024.0
    return String.format(Locale.US, "%d kB (%.1f MiB)", valueKb, mib)
}