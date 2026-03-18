package com.focusguard.ui

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.focusguard.R
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import java.util.Calendar
import java.util.concurrent.TimeUnit

class UsageStatsFragment : Fragment() {

    private lateinit var layoutTopApps: LinearLayout
    private lateinit var tvNoUsageData: TextView
    private lateinit var tvTrendLabel: TextView
    private lateinit var lineChart: LineChart

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_usage_stats, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        layoutTopApps = view.findViewById(R.id.layoutTopApps)
        tvNoUsageData = view.findViewById(R.id.tvNoUsageData)
        tvTrendLabel = view.findViewById(R.id.tvTrendLabel)
        lineChart = view.findViewById(R.id.lineChart)

        loadTop5Apps()
        loadMonthlyChart()
    }

    private fun loadTop5Apps() {
        val ctx = context ?: return
        try {
            val usageStatsManager =
                ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

            val calendar = Calendar.getInstance()
            val endTime = calendar.timeInMillis
            calendar.add(Calendar.DAY_OF_YEAR, -7)
            val startTime = calendar.timeInMillis

            val usageStatsList = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startTime,
                endTime
            )

            if (usageStatsList.isNullOrEmpty()) {
                tvNoUsageData.visibility = View.VISIBLE
                return
            }

            // Agrupa por pacote e soma o tempo total
            val usageMap = mutableMapOf<String, Long>()
            for (stats in usageStatsList) {
                if (stats.totalTimeInForeground > 0) {
                    val pkg = stats.packageName
                    usageMap[pkg] = (usageMap[pkg] ?: 0L) + stats.totalTimeInForeground
                }
            }

            // Filtra launchers e o próprio app
            val filteredMap = usageMap.filter { entry ->
                !entry.key.contains("launcher") &&
                entry.key != ctx.packageName &&
                entry.key != "com.android.settings" &&
                entry.value > TimeUnit.MINUTES.toMillis(1) // Mínimo 1 minuto
            }

            val top5 = filteredMap.entries
                .sortedByDescending { it.value }
                .take(5)

            if (top5.isEmpty()) {
                tvNoUsageData.visibility = View.VISIBLE
                return
            }

            tvNoUsageData.visibility = View.GONE
            layoutTopApps.removeAllViews()

            val pm = ctx.packageManager
            val iconSizePx = dpToPx(40f)
            val marginPx = dpToPx(8f)
            val barHeightPx = dpToPx(6f)
            val maxUsage = top5.maxOf { it.value }.toFloat()

            for ((index, entry) in top5.withIndex()) {
                val itemLayout = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, marginPx / 2, 0, marginPx / 2)
                    }
                    setPadding(0, dpToPx(6f), 0, dpToPx(6f))
                }

                // Número de ranking
                val rankText = TextView(ctx).apply {
                    text = "${index + 1}"
                    setTextColor(Color.parseColor("#FF00BCD4"))
                    textSize = 16f
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(dpToPx(28f), dpToPx(28f)).apply {
                        setMargins(0, 0, marginPx, 0)
                    }
                }
                itemLayout.addView(rankText)

                // Ícone do app
                try {
                    val icon = pm.getApplicationIcon(entry.key)
                    val imageView = ImageView(ctx).apply {
                        setImageDrawable(icon)
                        layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx).apply {
                            setMargins(0, 0, marginPx, 0)
                        }
                    }
                    itemLayout.addView(imageView)
                } catch (_: PackageManager.NameNotFoundException) {
                    // Se não achar o ícone, pula
                }

                // Nome + tempo
                val infoLayout = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                }

                val appName = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(entry.key, 0)).toString()
                } catch (_: PackageManager.NameNotFoundException) {
                    entry.key.substringAfterLast(".")
                }

                val nameText = TextView(ctx).apply {
                    text = appName
                    setTextColor(Color.parseColor("#FFFAFAFA"))
                    textSize = 14f
                    maxLines = 1
                }
                infoLayout.addView(nameText)

                val timeText = TextView(ctx).apply {
                    text = formatDuration(entry.value)
                    setTextColor(Color.parseColor("#FFB0B0B0"))
                    textSize = 12f
                }
                infoLayout.addView(timeText)

                // Barra de progresso visual
                val progressBg = LinearLayout(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        barHeightPx
                    ).apply {
                        setMargins(0, dpToPx(4f), 0, 0)
                    }
                    setBackgroundColor(Color.parseColor("#FF2A2A2E"))
                }

                val progressFill = View(ctx).apply {
                    val ratio = (entry.value.toFloat() / maxUsage).coerceIn(0f, 1f)
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        ratio
                    )
                    setBackgroundColor(Color.parseColor("#FF00BCD4"))
                }
                val progressEmpty = View(ctx).apply {
                    val ratio = 1f - (entry.value.toFloat() / maxUsage).coerceIn(0f, 1f)
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        ratio
                    )
                }
                progressBg.addView(progressFill)
                progressBg.addView(progressEmpty)
                infoLayout.addView(progressBg)

                itemLayout.addView(infoLayout)
                layoutTopApps.addView(itemLayout)
            }
        } catch (e: Exception) {
            tvNoUsageData.visibility = View.VISIBLE
            tvNoUsageData.text = "Sem dados de uso disponíveis.\nConceda a permissão de Acesso ao Uso."
        }
    }

    private fun loadMonthlyChart() {
        val ctx = context ?: return
        try {
            val usageStatsManager =
                ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

            val entries = mutableListOf<Entry>()
            val labels = mutableListOf<String>()

            val calendar = Calendar.getInstance()
            val monthNames = arrayOf("Jan", "Fev", "Mar", "Abr", "Mai", "Jun",
                "Jul", "Ago", "Set", "Out", "Nov", "Dez")

            for (i in 5 downTo 0) {
                val cal = Calendar.getInstance()
                cal.add(Calendar.MONTH, -i)
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                val monthStart = cal.timeInMillis

                val calEnd = Calendar.getInstance()
                calEnd.add(Calendar.MONTH, -i)
                calEnd.set(Calendar.DAY_OF_MONTH, calEnd.getActualMaximum(Calendar.DAY_OF_MONTH))
                calEnd.set(Calendar.HOUR_OF_DAY, 23)
                calEnd.set(Calendar.MINUTE, 59)
                calEnd.set(Calendar.SECOND, 59)
                val monthEnd = calEnd.timeInMillis

                val usageStatsList = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    monthStart,
                    monthEnd
                )

                var totalUsageMs = 0L
                var daysWithData = 0

                if (!usageStatsList.isNullOrEmpty()) {
                    val dailyMap = mutableMapOf<Int, Long>()
                    for (stats in usageStatsList) {
                        if (stats.totalTimeInForeground > 0) {
                            val dayCal = Calendar.getInstance()
                            dayCal.timeInMillis = stats.lastTimeUsed
                            val dayOfMonth = dayCal.get(Calendar.DAY_OF_MONTH)
                            dailyMap[dayOfMonth] =
                                (dailyMap[dayOfMonth] ?: 0L) + stats.totalTimeInForeground
                        }
                    }
                    totalUsageMs = dailyMap.values.sum()
                    daysWithData = dailyMap.size.coerceAtLeast(1)
                }

                val avgHoursPerDay = if (daysWithData > 0) {
                    totalUsageMs.toFloat() / daysWithData / 1000f / 3600f
                } else {
                    0f
                }

                entries.add(Entry((5 - i).toFloat(), avgHoursPerDay))
                labels.add(monthNames[cal.get(Calendar.MONTH)])
            }

            // Configurar gráfico de linha
            val dataSet = LineDataSet(entries, "Horas/dia").apply {
                color = Color.parseColor("#FF00BCD4")
                setCircleColor(Color.parseColor("#FF00BCD4"))
                lineWidth = 2.5f
                circleRadius = 4f
                setDrawCircleHole(true)
                circleHoleColor = Color.parseColor("#FF1C1C1E")
                circleHoleRadius = 2f
                setDrawValues(true)
                valueTextColor = Color.parseColor("#FFB0B0B0")
                valueTextSize = 10f
                setDrawFilled(true)
                fillColor = Color.parseColor("#FF00BCD4")
                fillAlpha = 30
                mode = LineDataSet.Mode.CUBIC_BEZIER
            }

            lineChart.apply {
                data = LineData(dataSet)
                description.isEnabled = false
                legend.isEnabled = false
                setTouchEnabled(true)
                isDragEnabled = false
                setScaleEnabled(false)
                setBackgroundColor(Color.TRANSPARENT)
                setDrawGridBackground(false)

                xAxis.apply {
                    position = XAxis.XAxisPosition.BOTTOM
                    textColor = Color.parseColor("#FF6B6B6B")
                    textSize = 11f
                    setDrawGridLines(false)
                    setDrawAxisLine(false)
                    valueFormatter = IndexAxisValueFormatter(labels)
                    granularity = 1f
                }

                axisLeft.apply {
                    textColor = Color.parseColor("#FF6B6B6B")
                    textSize = 10f
                    setDrawGridLines(true)
                    gridColor = Color.parseColor("#FF2A2A2E")
                    setDrawAxisLine(false)
                    axisMinimum = 0f
                }

                axisRight.isEnabled = false

                animateX(800)
                invalidate()
            }

            // Detectar tendência
            if (entries.size >= 2) {
                val firstHalf = entries.take(3).map { it.y }.average()
                val secondHalf = entries.takeLast(3).map { it.y }.average()
                val trend = if (secondHalf > firstHalf * 1.1) {
                    "📈 Tendência: uso SUBINDO"
                } else if (secondHalf < firstHalf * 0.9) {
                    "📉 Tendência: uso CAINDO — ótimo!"
                } else {
                    "➡️ Tendência: uso ESTÁVEL"
                }
                tvTrendLabel.text = trend
            }

        } catch (e: Exception) {
            tvTrendLabel.text = "Sem dados suficientes para o gráfico"
        }
    }

    private fun formatDuration(millis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        return if (hours > 0) {
            "${hours}h ${minutes}min"
        } else {
            "${minutes}min"
        }
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics
        ).toInt()
    }
}
