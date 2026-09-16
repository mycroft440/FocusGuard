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

private const val MAX_ANR_ENTRIES = 32
private const val MAX_TRACE_CHARS = 80_000

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
        appendLine("FocusGuard ANR report")
        appendLine("Generated: $generatedAt")
        appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Package: ${context.packageName}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine()
    }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        return@withContext header + context.getString(R.string.dev_mode_anr_unavailable)
    }

    header + collectAnrEntriesApi30(context)
}

@TargetApi(Build.VERSION_CODES.R)
private fun collectAnrEntriesApi30(context: Context): String {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val exits = runCatching {
        activityManager.getHistoricalProcessExitReasons(
            context.packageName,
            0,
            MAX_ANR_ENTRIES
        )
    }.getOrElse { emptyList() }

    val anrs = exits.filter { it.reason == ApplicationExitInfo.REASON_ANR }
    if (anrs.isEmpty()) {
        return context.getString(R.string.dev_mode_anr_empty)
    }

    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.getDefault())
    return buildString {
        anrs.forEachIndexed { index, info ->
            appendLine("===== ANR ${index + 1} =====")
            appendLine("Timestamp: ${formatter.format(Date(info.timestamp))}")
            appendLine("Process: ${info.processName ?: context.packageName}")
            appendLine("Importance: ${info.importance}")
            info.description?.takeIf { it.isNotBlank() }?.let { description ->
                appendLine("Description: $description")
            }

            val trace = readAnrTrace(info)
            if (trace.isNullOrBlank()) {
                appendLine("Trace: unavailable")
            } else {
                appendLine("Trace:")
                appendLine(trace)
            }
            appendLine()
        }
    }
}

@TargetApi(Build.VERSION_CODES.R)
private fun readAnrTrace(info: ApplicationExitInfo): String? = runCatching {
    info.traceInputStream?.bufferedReader()?.use { reader ->
        val output = StringBuilder()
        while (output.length < MAX_TRACE_CHARS) {
            val line = reader.readLine() ?: break
            val remaining = MAX_TRACE_CHARS - output.length
            if (line.length + 1 > remaining) {
                output.append(line.take(remaining.coerceAtLeast(0)))
                output.appendLine()
                output.append("[trace truncated]")
                break
            }
            output.appendLine(line)
        }
        output.toString()
    }
}.getOrNull()
