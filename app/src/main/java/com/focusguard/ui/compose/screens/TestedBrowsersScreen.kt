package com.focusguard.ui.compose.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.accessibility.website.compatibility.BrowserCompatibilityRecord
import com.focusguard.accessibility.website.compatibility.BrowserCompatibilityStatus
import com.focusguard.accessibility.website.compatibility.BrowserCompatibilityStore
import com.focusguard.ui.compose.layout.FocusGuardScreenScaffold
import com.focusguard.ui.compose.layout.FocusGuardScrollableContent
import com.focusguard.ui.compose.layout.FocusGuardSectionHeader
import com.focusguard.ui.compose.theme.CardBorder
import com.focusguard.ui.compose.theme.FocusCard

@Composable
fun TestedBrowsersScreen(onBack: () -> Unit) {
    val records by BrowserCompatibilityStore.testedRecords.collectAsState()
    val supported = records.filter { it.status == BrowserCompatibilityStatus.SUPPORTED }
    val unsupported = records.filter { it.status == BrowserCompatibilityStatus.UNSUPPORTED }

    FocusGuardScreenScaffold(
        title = stringResource(R.string.tested_browsers_title),
        onBack = onBack
    ) { paddingValues ->
        FocusGuardScrollableContent(paddingValues = paddingValues) {
            FocusGuardSectionHeader(stringResource(R.string.tested_browsers_supported))
            BrowserList(
                records = supported,
                emptyText = stringResource(R.string.tested_browsers_supported_empty)
            )

            Spacer(Modifier.height(24.dp))
            FocusGuardSectionHeader(stringResource(R.string.tested_browsers_unsupported))
            BrowserList(
                records = unsupported,
                emptyText = stringResource(R.string.tested_browsers_unsupported_empty)
            )
        }
    }
}

@Composable
private fun BrowserList(
    records: List<BrowserCompatibilityRecord>,
    emptyText: String
) {
    if (records.isEmpty()) {
        Text(
            text = emptyText,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp
        )
        return
    }

    Column {
        records.forEach { record -> BrowserCompatibilityRow(record) }
    }
}

@Composable
private fun BrowserCompatibilityRow(record: BrowserCompatibilityRecord) {
    val context = LocalContext.current
    val label = remember(record.packageName) {
        runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getApplicationInfo(record.packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(record.packageName)
    }

    FocusCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Text(
            text = label,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 13.dp),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
