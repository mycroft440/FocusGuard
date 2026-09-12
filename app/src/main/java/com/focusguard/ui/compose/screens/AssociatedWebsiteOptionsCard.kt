package com.focusguard.ui.compose.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.CardBorder
import com.focusguard.ui.compose.theme.DarkCard
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.utils.AssociatedBlockTargets

/**
 * Fixed opt-in shown beside the duration configuration rather than as a modal
 * interruption while targets are being selected.
 *
 * The caller owns the selected domains so the same UI can feed a temporary
 * time-block session or an independent website usage-limit rule.
 */
@Composable
fun AssociatedWebsiteOptionsCard(
    options: List<AssociatedBlockTargets.WebsiteCompanion>,
    selectedDomains: Set<String>,
    onSelectedDomainsChange: (Set<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    if (options.isEmpty()) return

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.associated_target_block_site_title),
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )

            options.forEach { option ->
                val selected = option.domain in selectedDomains
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSelectedDomainsChange(
                                if (selected) {
                                    selectedDomains - option.domain
                                } else {
                                    selectedDomains + option.domain
                                }
                            )
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = option.appName,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = option.domain,
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { checked ->
                            onSelectedDomainsChange(
                                if (checked) {
                                    selectedDomains + option.domain
                                } else {
                                    selectedDomains - option.domain
                                }
                            )
                        },
                        colors = CheckboxDefaults.colors(checkedColor = AccentCyan)
                    )
                }
            }
        }
    }
}
