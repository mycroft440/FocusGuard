package com.focusguard.ui.compose.components

import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import com.focusguard.R
import com.focusguard.data.PredefinedApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single app-icon renderer used by blocking and usage-limit screens.
 *
 * Installed applications always use Android's real launcher icon. Known apps that
 * are not installed can still be configured preventively, so they fall back to
 * an icon bundled with FocusGuard (for the most visible presets) or to the
 * preset's domain favicon. A branded local mark stays behind the remote image,
 * which means an offline device never renders an empty icon.
 */
@Composable
fun FocusGuardAppIcon(
    packageName: String,
    appName: String,
    modifier: Modifier = Modifier,
    iconUrl: String? = null,
    cornerRadius: Dp = 10.dp
) {
    val context = LocalContext.current
    var installedDrawable by remember(packageName) { mutableStateOf<Drawable?>(null) }

    LaunchedEffect(packageName) {
        installedDrawable = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
        }
    }

    val bundledIcon = remember(packageName) { bundledPredefinedIcon(packageName) }
    val remoteIconUrl = remember(packageName, iconUrl) {
        iconUrl?.takeIf { it.isNotBlank() } ?: predefinedFaviconUrl(packageName)
    }
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier.clip(shape),
        contentAlignment = Alignment.Center
    ) {
        BrandedAppFallback(packageName = packageName, appName = appName)

        when {
            installedDrawable != null -> {
                val bitmap = remember(installedDrawable) {
                    requireNotNull(installedDrawable).toBitmap(96, 96).asImageBitmap()
                }
                Image(
                    bitmap = bitmap,
                    contentDescription = appName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            bundledIcon != null -> {
                Image(
                    painter = painterResource(bundledIcon),
                    contentDescription = appName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            remoteIconUrl != null -> {
                AsyncImage(
                    model = remoteIconUrl,
                    contentDescription = appName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@DrawableRes
private fun bundledPredefinedIcon(packageName: String): Int? = when (packageName) {
    "com.instagram.android" -> R.drawable.ic_brand_instagram
    "com.facebook.katana" -> R.drawable.ic_brand_facebook
    "com.google.android.youtube" -> R.drawable.ic_brand_youtube
    else -> null
}

private fun predefinedFaviconUrl(packageName: String): String? =
    PredefinedApps.PREVENTIVE_APPS
        .firstOrNull { it.packageName == packageName }
        ?.domain
        ?.takeIf { it.isNotBlank() }
        ?.let { domain -> "https://www.google.com/s2/favicons?domain=$domain&sz=128" }

@Composable
private fun BrandedAppFallback(packageName: String, appName: String) {
    val background = remember(packageName) { fallbackBrandColor(packageName) }
    val mark = remember(packageName, appName) {
        when (packageName) {
            "com.instagram.android" -> "◎"
            "com.facebook.katana" -> "f"
            "com.google.android.youtube" -> "▶"
            "com.zhiliaoapp.musically" -> "♪"
            "com.twitter.android" -> "X"
            "com.netflix.mediaclient" -> "N"
            "com.spotify.music" -> "S"
            "com.discord" -> "D"
            else -> appName.trim().take(1).uppercase().ifBlank { "•" }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = mark,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

private fun fallbackBrandColor(packageName: String): Color = when {
    packageName.contains("instagram") -> Color(0xFFE4405F)
    packageName.contains("facebook") -> Color(0xFF1877F2)
    packageName.contains("youtube") -> Color(0xFFFF0000)
    packageName.contains("tiktok") -> Color(0xFF111111)
    packageName.contains("twitter") -> Color(0xFF111111)
    packageName.contains("netflix") -> Color(0xFFE50914)
    packageName.contains("spotify") -> Color(0xFF1DB954)
    packageName.contains("tinder") -> Color(0xFFFE3C72)
    packageName.contains("twitch") -> Color(0xFF9146FF)
    packageName.contains("discord") -> Color(0xFF5865F2)
    else -> Color(packageName.hashCode()).copy(alpha = 1f)
}
