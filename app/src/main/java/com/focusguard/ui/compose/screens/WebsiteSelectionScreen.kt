package com.focusguard.ui.compose.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.database.BlockedWebsite
import com.focusguard.ui.compose.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebsiteSelectionScreen(
    sites: List<BlockedWebsite>,
    onAddSite: (String) -> Unit,
    onRemoveSite: (BlockedWebsite) -> Unit,
    onBack: () -> Unit
) {
    var url by remember { mutableStateOf("") }

    val suggestedDomains = listOf("youtube.com", "instagram.com", "facebook.com", "reddit.com", "tiktok.com", "twitter.com")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Selecionar Sites", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
            )
        },
        containerColor = DarkBg
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // URL input
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = { Text("Ex: youtube.com", color = TextHint) },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentCyan,
                        cursorColor = AccentCyan,
                        unfocusedTextColor = TextPrimary,
                        focusedTextColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true
                )

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = {
                        if (url.isNotBlank()) {
                            onAddSite(url.trim())
                            url = ""
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                    contentPadding = PaddingValues(12.dp),
                    modifier = Modifier.height(56.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Adicionar")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Suggested chips
            Text(
                text = "Sugestões populares:",
                fontSize = 13.sp,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                items(suggestedDomains) { domain ->
                    SuggestionChip(
                        onClick = { onAddSite(domain) },
                        label = { Text(domain, fontSize = 12.sp) },
                        shape = RoundedCornerShape(12.dp),
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = DarkCardElevated,
                            labelColor = AccentCyan
                        ),
                        border = SuggestionChipDefaults.suggestionChipBorder(
                            enabled = true,
                            borderColor = CardBorder
                        )
                    )
                }
            }

            HorizontalDivider(color = Divider, thickness = 1.dp)

            Spacer(modifier = Modifier.height(16.dp))

            // Sites list
            Text(
                text = "${sites.size} site(s) bloqueado(s)",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(sites, key = { it.id }) { site ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = DarkCard),
                        border = BorderStroke(1.dp, CardBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🌐", fontSize = 20.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = site.domain,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = TextPrimary
                                )
                                Text(
                                    text = site.url,
                                    fontSize = 11.sp,
                                    color = TextHint
                                )
                            }
                            IconButton(onClick = { onRemoveSite(site) }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Remover",
                                    tint = DangerRed,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
