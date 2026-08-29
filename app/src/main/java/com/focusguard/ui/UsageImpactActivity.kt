package com.focusguard.ui

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button as AndroidButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView as AndroidTextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.focusguard.database.AppDatabase
import com.focusguard.monetization.FocusGuardAds
import com.focusguard.ui.compose.theme.DarkSurface
import com.focusguard.ui.compose.theme.FocusGuardTheme
import com.focusguard.ui.compose.theme.SuccessGreen
import com.focusguard.ui.compose.theme.TextPrimary
import com.focusguard.ui.compose.theme.TextSecondary
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UsageImpactActivity : AppCompatActivity() {
    private var nativeAd by mutableStateOf<NativeAd?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        if (packageName.isBlank()) {
            finish()
            return
        }

        setContent {
            FocusGuardTheme {
                UsageImpactScreen(
                    activity = this,
                    packageName = packageName,
                    nativeAd = nativeAd,
                    onClose = ::finish
                )
            }
        }

        FocusGuardAds.loadNative(
            activity = this,
            onLoaded = { loadedAd ->
                val previous = nativeAd
                nativeAd = loadedAd
                previous?.destroy()
            }
        )
    }

    override fun onDestroy() {
        nativeAd?.destroy()
        nativeAd = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PACKAGE_NAME = "usage_impact_package"

        fun createIntent(context: Context, packageName: String): Intent =
            Intent(context, UsageImpactActivity::class.java).apply {
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
    }
}

private data class UsageImpactSnapshot(
    val appName: String,
    val beforeMillis: Long,
    val afterMillis: Long,
    val windowMillis: Long,
    val dailyLimitMinutes: Int
)

@Composable
private fun UsageImpactScreen(
    activity: ComponentActivity,
    packageName: String,
    nativeAd: NativeAd?,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val snapshot by produceState<UsageImpactSnapshot?>(initialValue = null, packageName) {
        value = loadUsageImpact(context, packageName)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Impacto do bloqueio",
                color = TextPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Veja como seu uso mudou antes e depois de ativar o bloqueio ou limitador.",
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))

            val data = snapshot
            if (data == null) {
                CircularProgressIndicator()
            } else {
                Text(
                    text = data.appName,
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    UsageCard(
                        modifier = Modifier.weight(1f),
                        title = "Antes",
                        value = formatDuration(data.beforeMillis)
                    )
                    UsageCard(
                        modifier = Modifier.weight(1f),
                        title = "Depois",
                        value = formatDuration(data.afterMillis)
                    )
                }
                Spacer(Modifier.height(18.dp))

                val reduction = if (data.beforeMillis > 0L) {
                    ((1.0 - data.afterMillis.toDouble() / data.beforeMillis.toDouble()) * 100.0)
                        .roundToInt()
                } else null
                if (reduction != null) {
                    Text(
                        text = if (reduction >= 0) {
                            "Uso reduzido em ${reduction}%"
                        } else {
                            "Uso aumentou em ${-reduction}%"
                        },
                        color = if (reduction >= 0) SuccessGreen else TextSecondary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }

                if (nativeAd != null) {
                    Spacer(Modifier.height(24.dp))
                    NativeImpactAd(nativeAd)
                    Spacer(Modifier.height(24.dp))
                } else {
                    Spacer(Modifier.height(18.dp))
                }

                Text(
                    text = "Comparação com períodos equivalentes de ${formatWindow(data.windowMillis)}. " +
                        "Limite configurado: ${data.dailyLimitMinutes} min/dia.",
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    fontSize = 13.sp
                )
            }

            Spacer(Modifier.height(28.dp))
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Text("Voltar")
            }
            Spacer(Modifier.height(24.dp))
        }

        ImpactRevenueBanner(activity)
    }
}

@Composable
private fun NativeImpactAd(nativeAd: NativeAd) {
    key(nativeAd) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 260.dp),
            factory = { context -> createNativeImpactAdView(context, nativeAd) }
        )
    }
}

private fun createNativeImpactAdView(context: Context, nativeAd: NativeAd): NativeAdView {
    val density = context.resources.displayMetrics.density
    fun dp(value: Int): Int = (value * density).roundToInt()

    val root = NativeAdView(context).apply {
        setPadding(dp(16), dp(14), dp(16), dp(16))
        background = GradientDrawable().apply {
            setColor(Color.rgb(31, 34, 38))
            cornerRadius = dp(18).toFloat()
        }
    }

    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    val attribution = AndroidTextView(context).apply {
        text = "Anúncio"
        setTextColor(Color.LTGRAY)
        textSize = 11f
        setTypeface(typeface, Typeface.BOLD)
    }
    content.addView(attribution)

    val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(10))
    }

    val icon = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            marginEnd = dp(12)
        }
    }
    header.addView(icon)

    val headerText = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    val headline = AndroidTextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 2
    }
    val advertiser = AndroidTextView(context).apply {
        setTextColor(Color.LTGRAY)
        textSize = 12f
        maxLines = 1
    }
    headerText.addView(headline)
    headerText.addView(advertiser)
    header.addView(headerText)
    content.addView(header)

    val mediaView = MediaView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(170)
        )
    }
    content.addView(mediaView)

    val body = AndroidTextView(context).apply {
        setTextColor(Color.LTGRAY)
        textSize = 14f
        setPadding(0, dp(12), 0, dp(10))
        maxLines = 3
    }
    content.addView(body)

    val callToAction = AndroidButton(context).apply {
        isAllCaps = false
        minHeight = dp(48)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
    content.addView(callToAction)
    root.addView(content)

    root.headlineView = headline
    root.advertiserView = advertiser
    root.iconView = icon
    root.bodyView = body
    root.callToActionView = callToAction

    headline.text = nativeAd.headline.orEmpty()
    advertiser.text = nativeAd.advertiser.orEmpty()
    advertiser.visibility = if (nativeAd.advertiser.isNullOrBlank()) View.GONE else View.VISIBLE

    val iconDrawable = nativeAd.icon?.drawable
    if (iconDrawable != null) {
        icon.setImageDrawable(iconDrawable)
        icon.visibility = View.VISIBLE
    } else {
        icon.visibility = View.GONE
    }

    body.text = nativeAd.body.orEmpty()
    body.visibility = if (nativeAd.body.isNullOrBlank()) View.GONE else View.VISIBLE

    callToAction.text = nativeAd.callToAction.orEmpty()
    callToAction.visibility = if (nativeAd.callToAction.isNullOrBlank()) View.GONE else View.VISIBLE

    root.registerNativeAd(nativeAd, mediaView)
    return root
}

@Composable
private fun ImpactRevenueBanner(activity: ComponentActivity) {
    val context = LocalContext.current
    val widthDp = LocalConfiguration.current.screenWidthDp.coerceAtLeast(300)
    val adView = remember(context) { AdView(context) }

    LaunchedEffect(adView, widthDp) {
        FocusGuardAds.loadLargeAdaptiveBanner(
            activity = activity,
            adView = adView,
            widthDp = widthDp
        )
    }

    DisposableEffect(adView) {
        onDispose { adView.destroy() }
    }

    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        factory = { adView }
    )
}

@Composable
private fun UsageCard(modifier: Modifier, title: String, value: String) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, color = TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(value, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private suspend fun loadUsageImpact(context: Context, packageName: String): UsageImpactSnapshot =
    withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val limit = AppDatabase.getDatabase(context)
            .appUsageLimitDao()
            .getAllStatic()
            .firstOrNull { it.packageName == packageName }
        val activation = limit?.createdAt
            ?.takeIf { it > 0L && it < now }
            ?: (now - DAY_MILLIS)
        val elapsed = (now - activation).coerceAtLeast(MIN_WINDOW_MILLIS)
        val window = elapsed.coerceAtMost(MAX_WINDOW_MILLIS)
        val beforeStart = (activation - window).coerceAtLeast(0L)
        val afterEnd = (activation + window).coerceAtMost(now)
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        val before = manager?.queryAndAggregateUsageStats(beforeStart, activation)
            ?.get(packageName)?.totalTimeInForeground ?: 0L
        val after = manager?.queryAndAggregateUsageStats(activation, afterEnd)
            ?.get(packageName)?.totalTimeInForeground ?: 0L
        val label = runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)

        UsageImpactSnapshot(
            appName = label,
            beforeMillis = before,
            afterMillis = after,
            windowMillis = window,
            dailyLimitMinutes = limit?.dailyLimitMinutes ?: 0
        )
    }

private fun formatDuration(millis: Long): String {
    val totalMinutes = (millis / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) "${hours}h ${minutes}min" else "${minutes} min"
}

private fun formatWindow(millis: Long): String {
    val hours = millis.toDouble() / 3_600_000.0
    return when {
        hours >= 48.0 -> String.format(Locale.getDefault(), "%.1f dias", hours / 24.0)
        hours >= 1.0 -> String.format(Locale.getDefault(), "%.1f horas", hours)
        else -> "${(millis / 60_000L).coerceAtLeast(1L)} min"
    }
}

private const val MIN_WINDOW_MILLIS = 60_000L
private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
private const val MAX_WINDOW_MILLIS = 7L * DAY_MILLIS
