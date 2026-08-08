package com.focusguard.ui.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.security.BlockDurationPolicy
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary

private fun BlockDurationPolicy.Unit.labelRes(): Int = when (this) {
    BlockDurationPolicy.Unit.HOURS -> R.string.duration_unit_hours
    BlockDurationPolicy.Unit.DAYS -> R.string.duration_unit_days
    BlockDurationPolicy.Unit.MONTHS -> R.string.duration_unit_months
    BlockDurationPolicy.Unit.FOREVER -> R.string.duration_unit_forever
}

/**
 * Amount field plus unit dropdown: `[ 30 ] [ dias ▾ ]`.
 *
 * Choosing "para sempre" hides the amount field entirely rather than disabling
 * it — a greyed-out box still invites you to type a number that would mean
 * nothing, and this control arms blocks that cannot be undone.
 *
 * @param onDurationChange receives null while the input cannot arm anything, so
 *   the caller can keep its confirm button disabled instead of guessing.
 */
@Composable
fun BlockDurationPicker(
    unit: BlockDurationPolicy.Unit,
    amountText: String,
    onUnitChange: (BlockDurationPolicy.Unit) -> Unit,
    onAmountChange: (String) -> Unit,
    accent: Color = AccentCyan,
    modifier: Modifier = Modifier,
    /** Unidades ofertadas; "para sempre" só aparece onde a política permite. */
    units: List<BlockDurationPolicy.Unit> = BlockDurationPolicy.Unit.entries
) {
    var expanded by remember { mutableStateOf(false) }
    val showsAmount = BlockDurationPolicy.requiresAmount(unit)

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (showsAmount) {
            OutlinedTextField(
                value = amountText,
                onValueChange = { value ->
                    val digits = value.filter(Char::isDigit).take(5)
                    val clamped = digits.toIntOrNull()
                        ?.coerceAtMost(BlockDurationPolicy.maxAmountFor(unit))
                    onAmountChange(clamped?.toString() ?: digits)
                },
                modifier = Modifier.width(110.dp),
                placeholder = { Text("30", color = TextHint) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        } else {
            // "Para sempre" fills the row on its own — no orphan amount box.
            Surface(
                color = accent.copy(alpha = 0.12f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(R.string.duration_unit_forever),
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                )
            }
        }

        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.height(56.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(unit.labelRes()), color = TextPrimary)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = accent)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                units.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(stringResource(option.labelRes())) },
                        onClick = {
                            onUnitChange(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
