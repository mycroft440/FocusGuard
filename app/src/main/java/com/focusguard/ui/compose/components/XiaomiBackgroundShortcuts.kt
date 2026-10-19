package com.focusguard.ui.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.utils.XiaomiBackgroundSettings

/** Explicação e atalhos para o início automático e a economia de bateria da Xiaomi. */
@Composable
fun XiaomiBackgroundShortcuts(
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start
) {
    val context = LocalContext.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.xiaomi_background_desc),
            color = TextSecondary,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { XiaomiBackgroundSettings.openAutoStart(context) },
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    stringResource(R.string.xiaomi_background_autostart),
                    color = AccentCyan,
                    textAlign = TextAlign.Center
                )
            }
            OutlinedButton(
                onClick = { XiaomiBackgroundSettings.openBatterySaver(context) },
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    stringResource(R.string.xiaomi_background_battery),
                    color = AccentCyan,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
