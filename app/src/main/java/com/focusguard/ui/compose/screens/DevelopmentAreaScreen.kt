package com.focusguard.ui.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.focusguard.R
import com.focusguard.security.DevelopmentUninstallCoordinator
import com.focusguard.ui.compose.layout.FocusGuardScreenScaffold
import com.focusguard.ui.compose.layout.FocusGuardScrollableContent
import com.focusguard.ui.compose.theme.DangerRed
import kotlinx.coroutines.launch

@Composable
fun DevelopmentAreaScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var outcome by remember {
        mutableStateOf<DevelopmentUninstallCoordinator.Outcome?>(null)
    }

    fun relinquishAndUninstall() {
        if (working) return
        working = true
        outcome = null
        scope.launch {
            val result = DevelopmentUninstallCoordinator.relinquishAndOpenUninstall(
                context = context,
                password = password
            )
            working = false
            if (result != DevelopmentUninstallCoordinator.Outcome.STARTED) {
                outcome = result
            }
        }
    }

    val errorResource = when (outcome) {
        DevelopmentUninstallCoordinator.Outcome.INVALID_PASSWORD ->
            R.string.dev_area_invalid_password
        DevelopmentUninstallCoordinator.Outcome.UNAVAILABLE ->
            R.string.dev_area_unavailable
        DevelopmentUninstallCoordinator.Outcome.RELEASE_FAILED ->
            R.string.dev_area_release_failed
        DevelopmentUninstallCoordinator.Outcome.UNINSTALL_UI_FAILED ->
            R.string.dev_area_uninstall_failed
        else -> null
    }

    FocusGuardScreenScaffold(
        title = stringResource(R.string.dev_area_title),
        onBack = onBack
    ) { paddingValues ->
        FocusGuardScrollableContent(paddingValues = paddingValues) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                )
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeveloperMode,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = stringResource(R.string.dev_area_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.dev_area_description),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = DangerRed.copy(alpha = 0.12f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = DangerRed
                    )
                    Text(
                        text = stringResource(R.string.dev_area_warning),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = password,
                // Intentionally no take(), maxLength or length filter: the field
                // accepts any amount of input and validates only on submission.
                onValueChange = {
                    password = it
                    outcome = null
                },
                label = { Text(stringResource(R.string.dev_area_password_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { relinquishAndUninstall() }
                ),
                isError = errorResource != null,
                modifier = Modifier.fillMaxWidth()
            )

            errorResource?.let { resource ->
                Spacer(Modifier.height(8.dp))
                Text(text = stringResource(resource), color = DangerRed)
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { relinquishAndUninstall() },
                enabled = password.isNotBlank() && !working,
                colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (working) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onError
                    )
                } else {
                    Text(
                        text = stringResource(R.string.dev_area_relinquish_action),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
