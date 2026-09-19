package com.focusguard.ui.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.focusguard.R

private enum class DeveloperModeDestination {
    MENU,
    MAINTENANCE,
    WEBSITE_DIAGNOSTICS
}

/** Keeps developer-only diagnostics inside the existing Modo Dev entry point. */
@Composable
internal fun DeveloperModeHostDialog(
    onDismiss: () -> Unit
) {
    var destination by remember { mutableStateOf(DeveloperModeDestination.MENU) }

    when (destination) {
        DeveloperModeDestination.MAINTENANCE -> DeveloperModeDialog(
            onDismiss = { destination = DeveloperModeDestination.MENU }
        )

        DeveloperModeDestination.WEBSITE_DIAGNOSTICS -> WebsiteBlockingDiagnosticsDialog(
            onDismiss = { destination = DeveloperModeDestination.MENU }
        )

        DeveloperModeDestination.MENU -> Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
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
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.dev_mode_close))
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.dev_mode_warning),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(Modifier.height(28.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { destination = DeveloperModeDestination.WEBSITE_DIAGNOSTICS }
                    ) {
                        Icon(Icons.Default.BugReport, contentDescription = null)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.dev_mode_website_diagnostics_title))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.dev_mode_website_diagnostics_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { destination = DeveloperModeDestination.MAINTENANCE }
                    ) {
                        Icon(Icons.Default.Build, contentDescription = null)
                        Text(stringResource(R.string.dev_mode_tools_title))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.dev_mode_tools_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
