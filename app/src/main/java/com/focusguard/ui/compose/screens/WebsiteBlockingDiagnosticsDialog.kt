package com.focusguard.ui.compose.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.focusguard.R
import com.focusguard.accessibility.website.diagnostics.WebsiteBlockingDiagnosticReport
import com.focusguard.accessibility.website.diagnostics.WebsiteBlockingDiagnostics
import com.focusguard.accessibility.website.diagnostics.WebsiteBlockingDiagnosticsClearResult
import com.focusguard.ui.compose.theme.CardBorder
import com.focusguard.ui.compose.theme.FocusCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun WebsiteBlockingDiagnosticsDialog(
    onDismiss: () -> Unit
) {
    var reports by remember { mutableStateOf<List<WebsiteBlockingDiagnosticReport>>(emptyList()) }
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var selectedContent by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshNonce by remember { mutableIntStateOf(0) }
    var showClearConfirmation by remember { mutableStateOf(false) }
    var clearResult by remember { mutableStateOf<WebsiteBlockingDiagnosticsClearResult?>(null) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(refreshNonce) {
        loading = true
        reports = withContext(Dispatchers.IO) { WebsiteBlockingDiagnostics.listReports() }
        loading = false
    }

    LaunchedEffect(selectedFileName) {
        val fileName = selectedFileName
        if (fileName == null) {
            selectedContent = null
        } else {
            loading = true
            selectedContent = withContext(Dispatchers.IO) {
                WebsiteBlockingDiagnostics.readReport(fileName)
            }
            loading = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            if (selectedFileName == null) {
                ReportList(
                    reports = reports,
                    loading = loading,
                    clearResult = clearResult,
                    onOpen = { selectedFileName = it },
                    onRefresh = {
                        clearResult = null
                        refreshNonce++
                    },
                    onClear = { showClearConfirmation = true },
                    onDismiss = onDismiss
                )
            } else {
                ReportDetail(
                    fileName = selectedFileName.orEmpty(),
                    content = selectedContent,
                    loading = loading,
                    onBack = { selectedFileName = null }
                )
            }
        }
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = {
                Text(stringResource(R.string.website_diagnostics_clear_title))
            },
            text = {
                Text(stringResource(R.string.website_diagnostics_clear_message, reports.size))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmation = false
                        coroutineScope.launch {
                            loading = true
                            val result = withContext(Dispatchers.IO) {
                                WebsiteBlockingDiagnostics.clearReports()
                            }
                            reports = withContext(Dispatchers.IO) {
                                WebsiteBlockingDiagnostics.listReports()
                            }
                            clearResult = result
                            loading = false
                        }
                    }
                ) {
                    Text(
                        text = stringResource(R.string.website_diagnostics_clear_confirm),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) {
                    Text(stringResource(R.string.website_diagnostics_cancel))
                }
            }
        )
    }
}

@Composable
private fun ReportList(
    reports: List<WebsiteBlockingDiagnosticReport>,
    loading: Boolean,
    clearResult: WebsiteBlockingDiagnosticsClearResult?,
    onOpen: (String) -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val formatter = remember {
        SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
    }
    val totalBytes = reports.sumOf { it.sizeBytes }

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
                text = stringResource(R.string.website_diagnostics_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.website_diagnostics_close))
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.website_diagnostics_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.website_diagnostics_reports_count, reports.size),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = stringResource(R.string.website_diagnostics_storage_size, totalBytes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))

        clearResult?.let { result ->
            Text(
                text = if (result.failedCount == 0) {
                    stringResource(
                        R.string.website_diagnostics_clear_result,
                        result.deletedCount
                    )
                } else {
                    stringResource(
                        R.string.website_diagnostics_clear_result_partial,
                        result.deletedCount,
                        result.failedCount
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (result.failedCount == 0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
            Spacer(Modifier.height(10.dp))
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading,
            onClick = onRefresh
        ) {
            Text(stringResource(R.string.website_diagnostics_refresh))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading && reports.isNotEmpty(),
            onClick = onClear
        ) {
            Text(
                text = stringResource(R.string.website_diagnostics_clear),
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(18.dp))

        if (loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (reports.isEmpty()) {
            Text(
                text = stringResource(R.string.website_diagnostics_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            reports.forEach { report ->
                FocusCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    border = BorderStroke(1.dp, CardBorder),
                    onClick = { onOpen(report.fileName) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp)
                        ) {
                            Text(
                                text = formatter.format(Date(report.modifiedAtMillis)),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = report.fileName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(
                                    R.string.website_diagnostics_report_size,
                                    report.sizeBytes
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ReportDetail(
    fileName: String,
    content: String?,
    loading: Boolean,
    onBack: () -> Unit
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
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.website_diagnostics_back))
            }
            Text(
                text = fileName,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(16.dp))
        when {
            loading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            content == null -> Text(
                text = stringResource(R.string.website_diagnostics_read_error),
                color = MaterialTheme.colorScheme.error
            )

            else -> SelectionContainer {
                Text(
                    text = content,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
