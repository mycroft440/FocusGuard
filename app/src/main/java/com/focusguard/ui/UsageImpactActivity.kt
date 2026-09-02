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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.Dp
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
import com.focusguard.usage.UsageInterventionStore
import com.focusguard.usage.UsageInterventionType
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UsageImpactActivity : AppCompatActivity() {
    private var nativeAd by mutableStateOf<NativeAd?>(null)
    private var targetPackage by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        if (targetPackage.isBlank()) {
            finish()
            return
        }

        setContent {
            FocusGuardTheme {
                UsageImpactScreen(
                    activity = this,
                    packageName = targetPackage,
                    nativeAd = nativeAd,
                    onClose = ::finish
                )
            }
        }

        loadNativeAd()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val nextPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        if (nextPackage.isBlank()) {
            finish()
            return
        }
        targetPackage = nextPackage
    }

    private fun loadNativeAd() {
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
                addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
    }
}

private data class UsageImpactSnapshot(
    val appName: String,
    val beforeMillis: Long,
    val afterMillis: Long,
    val windowMillis: Long,
    val interventionType: UsageInterventionType,
    val dailyLimitMinutes: Int?,
    val endsAt: Long?
)

/**
 * Fixed, non-scrollable impact surface.
 *
 * The previous layout combined a tall native ad, generous spacers and a large
 * anchored banner inside a scrolling column. On common phones the user had to
 * scroll just to reach the comparison text and Back button. This layout reserves
 * one compact native-ad slot and keeps the adaptive banner anchored by Scaffold,
 * so the entire block result remains on a single screen.
 */
@Composable
private fun UsageImpactScreen(
    activity: ComponentActivity,
    packageName: String,
    nativeAd: NativeAd?,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val compact = configuration.screenHeightDp <= 760
    val snapshot by produceState<UsageImpactSnapshot?>(initialValue = null, packageName) {
        value = loadUsageImpact(context, packageName)
    }

    val horizontalPadding = if (compact) 16.dp else 20.dp
    val nativeHeight = if (compact) 172.dp else 204.dp

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = { ImpactRevenueBanner(activity) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = horizontalPadding, vertical = if (compact) 8.dp else 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Impacto do bloqueio",
                color = TextPrimary,
                fontSize = if (compact) 21.sp else 23.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Spacer(Modifier.height(if (compact) 2.dp else 4.dp))
            Text(
                text = "Compare o uso antes e depois do bloqueio.",
                color = TextSecondary,
                textAlign = TextAlign.Center,
                fontSize = if (compact) 11.sp else 12.sp,
                maxLines = 1
            )
            Spacer(Modifier.height(if (compact) 6.dp else 9.dp))

            val data = snapshot
            if (data == null) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(34.dp))
                }
            } else {
                Text(
                    text = data.appName,
                    color = TextPrimary,
                    fontSize = if (compact) 16.sp else 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Spacer(Modifier.height(if (compact) 6.dp else 8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    UsageCard(
                        modifier = Modifier.weight(1f),
                        title = "Antes",
                        value = formatDuration(data.beforeMillis),
                        compact = compact
                    )
                    UsageCard(
                        modifier = Modifier.weight(1f),
                        title = "Depois",
                        value = formatDuration(data.afterMillis),
                        compact = compact
                    )
                }
                Spacer(Modifier.height(if (compact) 6.dp else 9.dp))

                val reduction = if (data.beforeMillis > 0L) {
                    ((1.0 - data.afterMillis.toDouble() / data.beforeMillis.toDouble()) * 100.0)
                        .roundToInt()
                } else null
                Text(
                    text = when {
                        reduction == null -> "Bloqueio ativo"
                        reduction >= 0 -> "Uso reduzido em ${reduction}%"
                        else -> "Uso aumentou em ${-reduction}%"
                    },
                    color = if (reduction == null || reduction >= 0) SuccessGreen else TextSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (compact) 15.sp else 17.sp,
                    maxLines = 1
                )
                Spacer(Modifier.height(if (compact) 6.dp else 8.dp))

                ImpactNativeAdSlot(
                    nativeAd = nativeAd,
                    compact = compact,
                    height = nativeHeight
                )
                Spacer(Modifier.height(if (compact) 5.dp else 7.dp))

                Text(
                    text = impactDescription(data),
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    fontSize = if (compact) 10.sp else 11.sp,
                    lineHeight = if (compact) 12.sp else 14.sp,
                    maxLines = 2
                )
                Spacer(Modifier.height(if (compact) 5.dp else 7.dp))

                Button(
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth().height(if (compact) 42.dp else 46.dp)
                ) {
                    Text("Voltar", fontSize = if (compact) 14.sp else 15.sp)
                }
            }
        }
    }
}

private fun impactDescription(data: UsageImpactSnapshot): String {
    val period = "Períodos equivalentes de ${formatWindow(data.windowMillis)}."
    return when (data.interventionType) {
        UsageInterventionType.TIME_BLOCK -> {
            val end = data.endsAt?.takeIf { it > System.currentTimeMillis() }
            if (end != null) {
                "$period Bloqueio ativo até ${formatTimestamp(end)}."
            } else {
                "$period Bloqueio por tempo ativo."
            }
        }
        UsageInterventionType.USAGE_LIMIT -> {
            val limit = data.dailyLimitMinutes
            if (limit != null) {
                "$period Limite: $limit min/dia."
            } else {
                "$period Limitador diário ativo."
            }
        }
    }
}

@Composable
private fun ImpactNativeAdSlot(
    nativeAd: NativeAd?,
    compact: Boolean,
    height: Dp
) {
    if (nativeAd == null) {
        Card(
            modifier = Modifier.fillMaxWidth().height(height),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
        }
        return
    }

    key(nativeAd, compact) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(height),
            factory = { context -> createNativeImpactAdView(context, nativeAd, compact) }
        )
    }
}

private fun createNativeImpactAdView(
    context: Context,
    nativeAd: NativeAd,
    compact: Boolean
): NativeAdView {
    val density = context.resources.displayMetrics.density
    fun dp(value: Int): Int = (value * density).roundToInt()

    val root = NativeAdView(context).apply {
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply {
            setColor(Color.rgb(31, 34, 38))
            cornerRadius = dp(16).toFloat()
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
        textSize = 9f
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 1
    }
    content.addView(attribution)

    val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(4), 0, dp(4))
    }

    val icon = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
            marginEnd = dp(8)
        }
    }
    header.addView(icon)

    val headerText = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    val headline = AndroidTextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = if (compact) 13f else 14f
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 1
    }
    val advertiser = AndroidTextView(context).apply {
        setTextColor(Color.LTGRAY)
        textSize = 9f
        maxLines = 1
    }
    headerText.addView(headline)
    headerText.addView(advertiser)
    header.addView(headerText)
    content.addView(header)

    val mediaView = MediaView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(if (compact) 58 else 78)
        )
    }
    content.addView(mediaView)

    val body = AndroidTextView(context).apply {
        setTextColor(Color.LTGRAY)
        textSize = if (compact) 10f else 11f
        setPadding(0, dp(4), 0, dp(3))
        maxLines = 1
    }
    content.addView(body)

    val callToAction = AndroidButton(context).apply {
        isAllCaps = false
        textSize = if (compact) 11f else 12f
        minHeight = dp(if (compact) 32 else 36)
        minimumHeight = dp(if (compact) 32 else 36)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(if (compact) 34 else 38)
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
    val widthDp = LocalConfiguration.current.screenWidthDp.coerceAtLeast(300)
    val adView = remember(activity) { AdView(activity) }

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
private fun UsageCard(
    modifier: Modifier,
    title: String,
    value: String,
    compact: Boolean
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = if (compact) 8.dp else 11.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, color = TextSecondary, fontSize = if (compact) 11.sp else 12.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                value,
                color = TextPrimary,
                fontSize = if (compact) 18.sp else 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
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
        val intervention = UsageInterventionStore.readApp(context, packageName)
            ?: limit?.let { UsageInterventionStore.syncFromLimit(context, it) }
        val activation = intervention?.startedAt
            ?.takeIf { it in 1 until now }
            ?: limit?.createdAt?.takeIf { it in 1 until now }
            ?: now
        val elapsed = (now - activation).coerceAtLeast(1L)
        val window = minOf(elapsed, activation).coerceAtMost(MAX_WINDOW_MILLIS)
            .coerceAtLeast(1L)
        val beforeStart = activation - window
        val afterEnd = activation + window
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        val before = manager?.queryAndAggregateUsageStats(beforeStart, activation)
            ?.get(packageName)?.totalTimeInForeground ?: 0L
        val after = manager?.queryAndAggregateUsageStats(activation, afterEnd)
            ?.get(packageName)?.totalTimeInForeground ?: 0L
        val label = runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
        val type = intervention?.type ?: if (limit?.lockMode.equals("TIME", true)) {
            UsageInterventionType.TIME_BLOCK
        } else {
            UsageInterventionType.USAGE_LIMIT
        }

        UsageImpactSnapshot(
            appName = label,
            beforeMillis = before,
            afterMillis = after,
            windowMillis = window,
            interventionType = type,
            dailyLimitMinutes = intervention?.dailyLimitMinutes
                ?: limit?.dailyLimitMinutes?.takeIf { it > 0 },
            endsAt = intervention?.endsAt ?: limit?.lockUntilTimestamp
        )
    }

private fun formatDuration(millis: Long): String {
    val totalMinutes = (millis / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) "${hours}h ${minutes}min" else "${minutes} min"
}

private fun formatWindow(millis: Long): String {
    val seconds = (millis / 1_000L).coerceAtLeast(1L)
    val minutes = seconds / 60L
    val hours = millis.toDouble() / 3_600_000.0
    return when {
        hours >= 48.0 -> String.format(Locale.getDefault(), "%.1f dias", hours / 24.0)
        hours >= 1.0 -> String.format(Locale.getDefault(), "%.1f horas", hours)
        minutes >= 1L -> "$minutes min"
        else -> "$seconds s"
    }
}

private fun formatTimestamp(timestamp: Long): String =
    SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(timestamp))

private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
private const val MAX_WINDOW_MILLIS = 7L * DAY_MILLIS