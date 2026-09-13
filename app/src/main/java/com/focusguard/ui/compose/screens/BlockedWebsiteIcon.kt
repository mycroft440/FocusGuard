package com.focusguard.ui.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.focusguard.data.PredefinedWebsites
import com.focusguard.utils.WebsiteBlocker

/**
 * Resolves the favicon domain used by the protected-target list.
 *
 * Predefined sites reuse the same [PredefinedWebsites.WebsiteInfo.iconDomain]
 * chosen by the site picker (notably X/Twitter). Plain custom domains can use
 * their own favicon. Categories and keyword rules deliberately stay local,
 * because they do not represent a single website that has a meaningful favicon.
 */
internal fun blockedWebsiteIconDomain(identifier: String): String? {
    val normalized = WebsiteBlocker.normalizeRule(identifier)
    if (
        normalized.isBlank() ||
        normalized == PredefinedWebsites.PORNOGRAPHY_RULE ||
        normalized.startsWith("keyword:")
    ) {
        return null
    }

    val preset = PredefinedWebsites.ALL_PRESETS.firstOrNull { website ->
        WebsiteBlocker.normalizeRule(website.domain) == normalized
    }
    if (preset != null) return preset.iconDomain

    return normalized.takeIf { rule ->
        '.' in rule && ':' !in rule && '*' !in rule
    }
}

@Composable
internal fun BlockedWebsiteIcon(
    identifier: String,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val normalized = remember(identifier) { WebsiteBlocker.normalizeRule(identifier) }
    val iconDomain = remember(identifier) { blockedWebsiteIconDomain(identifier) }
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = modifier
            .size(34.dp)
            .clip(shape)
            .background(accent.copy(alpha = 0.14f))
            .border(1.dp, accent.copy(alpha = 0.24f), shape),
        contentAlignment = Alignment.Center
    ) {
        when {
            normalized == PredefinedWebsites.PORNOGRAPHY_RULE -> {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = label,
                    tint = accent,
                    modifier = Modifier.size(20.dp)
                )
            }

            iconDomain != null -> {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(context)
                        .data("https://www.google.com/s2/favicons?domain=$iconDomain&sz=128")
                        .crossfade(true)
                        .build(),
                    contentDescription = label,
                    modifier = Modifier.fillMaxSize().padding(6.dp),
                    contentScale = ContentScale.Fit,
                    loading = {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                color = accent,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    error = {
                        WebsiteIconFallback(label = label, accent = accent)
                    }
                )
            }

            else -> WebsiteIconFallback(label = label, accent = accent)
        }
    }
}

@Composable
private fun WebsiteIconFallback(label: String, accent: Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = label.take(1).uppercase(),
            color = accent,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
    }
}
