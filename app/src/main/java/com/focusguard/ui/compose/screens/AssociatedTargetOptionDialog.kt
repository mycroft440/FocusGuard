package com.focusguard.ui.compose.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.DarkSurface
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.utils.AssociatedBlockTargets

@Composable
internal fun AssociatedTargetOptionDialog(
    title: String,
    message: String,
    onDecision: (Boolean) -> Unit
) {
    var blockAlso by remember(title, message) {
        mutableStateOf(AssociatedBlockTargets.DEFAULT_BLOCK_COMPANION)
    }

    AlertDialog(
        onDismissRequest = { onDecision(false) },
        title = { Text(title, color = TextPrimary) },
        text = {
            Column {
                Text(message, color = TextSecondary, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = !blockAlso,
                        onClick = { blockAlso = false }
                    )
                    Text(stringResource(R.string.associated_target_no), color = TextPrimary)
                    Spacer(modifier = Modifier.width(24.dp))
                    RadioButton(
                        selected = blockAlso,
                        onClick = { blockAlso = true }
                    )
                    Text(stringResource(R.string.associated_target_yes), color = TextPrimary)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onDecision(blockAlso) },
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
            ) {
                Text(stringResource(R.string.associated_target_continue), color = DarkBg)
            }
        },
        containerColor = DarkSurface,
        shape = RoundedCornerShape(24.dp)
    )
}
