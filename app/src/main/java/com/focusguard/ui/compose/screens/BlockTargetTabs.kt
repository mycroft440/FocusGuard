package com.focusguard.ui.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import com.focusguard.data.PredefinedWebsites
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.CardBorder
import com.focusguard.ui.compose.theme.DangerRed
import com.focusguard.ui.compose.theme.DarkBg
import com.focusguard.ui.compose.theme.TextHint
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import com.focusguard.utils.WebsiteBlocker

/**
 * Site picker used inside the block wizard.
 *
 * Offers the same preset shortcuts the app has always had for sites — the
 * popular domains plus the pornography category — next to a field for anything
 * else, because a personal distraction is rarely on a curated list.
 *
 * Only domains are accepted here. A bare word is a valid rule elsewhere in the
 * app, but silently turning a mistyped domain into a "block every site whose
 * name contains this" rule is not what someone typing in the sites tab meant;
 * words have their own tab where that is the stated intent.
 *
 * @param rules every rule currently chosen, sites and words alike, so a preset
 *   already added from another tab shows as selected instead of duplicating.
 * @param blockedRules rules another protection already covers, rejected with a
 *   message rather than silently dropped at save time.
 */
@Composable
fun WebsiteRulesTab(
    rules: List<String>,
    blockedRules: Set<String>,
    onRulesChange: (List<String>) -> Unit,
    onAlreadyBlocked: () -> Unit,
    modifier: Modifier = Modifier
) {
    var input by remember { mutableStateOf("") }
    var invalidInput by remember { mutableStateOf(false) }

    fun toggle(rule: String) {
        if (rule.isEmpty()) return
        if (rule in rules) {
            onRulesChange(rules.filterNot { it == rule })
        } else if (isWebsiteRuleAlreadyBlocked(rule, blockedRules)) {
            onAlreadyBlocked()
        } else {
            onRulesChange((rules + rule).distinct())
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            RuleInputRow(
                value = input,
                onValueChange = { input = it; invalidInput = false },
                placeholder = stringResource(R.string.block_targets_site_placeholder),
                icon = Icons.Default.Public,
                isError = invalidInput,
                onAdd = {
                    // Só domínio: normalizeRule aceitaria a palavra solta e a
                    // gravaria como keyword, o que não é o que se pede aqui.
                    val domain = WebsiteBlocker.extractDomain(input)
                    if (domain.isEmpty()) {
                        invalidInput = true
                    } else {
                        toggle(domain)
                        input = ""
                    }
                }
            )
        }
        item {
            Text(
                text = if (invalidInput) {
                    stringResource(R.string.block_targets_site_invalid)
                } else {
                    stringResource(R.string.block_targets_site_helper)
                },
                color = if (invalidInput) DangerRed else TextHint,
                fontSize = 12.sp
            )
        }
        item { SectionLabel(stringResource(R.string.protection_sites_common)) }
        item {
            PornographyPresetRow(
                selected = PredefinedWebsites.PORNOGRAPHY_RULE in rules,
                onToggle = { toggle(PredefinedWebsites.PORNOGRAPHY_RULE) }
            )
        }
        items(PredefinedWebsites.ALL_PRESETS, key = { "preset_${it.domain}" }) { website ->
            val normalized = WebsiteBlocker.normalizeRule(website.domain)
            WebsitePresetRow(
                website = website,
                selected = normalized in rules,
                onToggle = { toggle(normalized) }
            )
        }

        val chosenSites = rules.filterNot(WebsiteBlocker::isKeywordRule)
        item { SelectedSectionHeader(stringResource(R.string.protection_sites_selected)) }
        if (chosenSites.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.protection_sites_none),
                    color = TextHint,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
        } else {
            items(chosenSites, key = { "chosen_$it" }) { rule ->
                ProtectionTargetRow(
                    icon = Icons.Default.Public,
                    title = WebsiteBlocker.displayRule(rule),
                    subtitle = stringResource(R.string.block_targets_tab_sites),
                    onRemove = { onRulesChange(rules.filterNot { it == rule }) }
                )
            }
        }
    }
}

/**
 * Word picker, offered only by the dopamine fast.
 *
 * A word blocks any domain containing it, so it is the widest net the app can
 * cast and the reason the fast can hold against a habit rather than a list of
 * addresses. The rule is stored with the same `keyword:` prefix the matcher
 * already understands.
 */
@Composable
fun KeywordRulesTab(
    rules: List<String>,
    blockedRules: Set<String>,
    onRulesChange: (List<String>) -> Unit,
    onAlreadyBlocked: () -> Unit,
    modifier: Modifier = Modifier
) {
    var input by remember { mutableStateOf("") }
    var invalidInput by remember { mutableStateOf(false) }
    val chosenKeywords = rules.filter(WebsiteBlocker::isKeywordRule)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            RuleInputRow(
                value = input,
                onValueChange = { input = it; invalidInput = false },
                placeholder = stringResource(R.string.block_targets_keyword_placeholder),
                icon = Icons.Default.Tag,
                isError = invalidInput,
                onAdd = {
                    val keyword = WebsiteBlocker.normalizeRule("keyword:$input")
                    when {
                        keyword.isEmpty() -> invalidInput = true
                        keyword in rules -> input = ""
                        isWebsiteRuleAlreadyBlocked(keyword, blockedRules) -> {
                            onAlreadyBlocked()
                            input = ""
                        }
                        else -> {
                            onRulesChange((rules + keyword).distinct())
                            input = ""
                        }
                    }
                }
            )
        }
        item {
            Text(
                text = if (invalidInput) {
                    stringResource(R.string.block_targets_keyword_invalid)
                } else {
                    stringResource(R.string.block_targets_keyword_helper)
                },
                color = if (invalidInput) DangerRed else TextHint,
                fontSize = 12.sp
            )
        }
        item { SelectedSectionHeader(stringResource(R.string.block_targets_keyword_selected)) }
        if (chosenKeywords.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.block_targets_keyword_none),
                    color = TextHint,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
        } else {
            items(chosenKeywords, key = { "keyword_$it" }) { rule ->
                ProtectionTargetRow(
                    icon = Icons.Default.Tag,
                    title = WebsiteBlocker.displayRule(rule),
                    subtitle = stringResource(R.string.block_targets_tab_keywords),
                    onRemove = { onRulesChange(rules.filterNot { it == rule }) }
                )
            }
        }
    }
}

@Composable
private fun RuleInputRow(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icon: ImageVector,
    isError: Boolean,
    onAdd: () -> Unit
) {
    Row(verticalAlignment = Alignment.Top) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(placeholder, color = TextHint) },
            leadingIcon = { Icon(icon, contentDescription = null, tint = TextHint) },
            isError = isError,
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentCyan,
                cursorColor = AccentCyan,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            ),
            shape = RoundedCornerShape(16.dp)
        )
        Spacer(Modifier.width(10.dp))
        Button(
            onClick = onAdd,
            enabled = value.isNotBlank(),
            modifier = Modifier.height(56.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = DarkBg)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = TextSecondary,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun SelectedSectionHeader(text: String) {
    Column {
        HorizontalDivider(color = CardBorder, modifier = Modifier.padding(vertical = 8.dp))
        SectionLabel(text)
    }
}
