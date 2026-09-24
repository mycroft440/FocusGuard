package com.focusguard.ui.compose.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.focusguard.R
import com.focusguard.monetization.FocusGuardAds
import com.focusguard.monetization.PremiumStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PromotionalCodeDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val premium by rememberPremiumStatus()
    var code by rememberSaveable { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(stringResource(R.string.promo_code_title)) },
        text = {
            Column {
                Text(stringResource(if (premium) R.string.promo_code_success else R.string.promo_code_description))
                if (!premium) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it; error = null },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !working,
                        singleLine = true,
                        label = { Text(stringResource(R.string.promo_code_title)) },
                        isError = error != null,
                        supportingText = { error?.let { Text(stringResource(it)) } }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !working && (premium || code.isNotBlank()),
                onClick = {
                    if (premium) {
                        onDismiss()
                    } else {
                        working = true
                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    PremiumStateStore.redeem(context, code)
                                }
                                when (result) {
                                    PremiumStateStore.RedemptionResult.ACTIVATED -> FocusGuardAds.stopForPremium()
                                    PremiumStateStore.RedemptionResult.INVALID_CODE -> error = R.string.promo_code_invalid
                                    PremiumStateStore.RedemptionResult.SAVE_FAILED -> error = R.string.promo_code_save_failed
                                }
                            } finally {
                                working = false
                            }
                        }
                    }
                }
            ) {
                Text(stringResource(if (premium) R.string.promo_code_close else R.string.promo_code_activate))
            }
        },
        dismissButton = {
            if (!premium) {
                TextButton(onClick = onDismiss, enabled = !working) { Text(stringResource(R.string.cancel)) }
            }
        }
    )
}
