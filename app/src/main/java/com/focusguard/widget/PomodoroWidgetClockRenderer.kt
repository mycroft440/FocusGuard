package com.focusguard.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.focusguard.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Rasteriza para RemoteViews o mesmo relógio usado pela tela Pomodoro em Compose.
 * App widgets clássicos não conseguem hospedar o Canvas do Compose diretamente,
 * então o desenho é reproduzido em um Bitmap com as mesmas proporções e cores.
 */
internal object PomodoroWidgetClockRenderer {
    private const val REFERENCE_DP = 205f

    fun render(
        context: Context,
        minutes: Int,
        maxMinutes: Int,
        activeProgress: Float?,
        remainingMillis: Long
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val side = min((205f * density).toInt().coerceAtLeast(320), 520)
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val unit = side / REFERENCE_DP
        val center = side / 2f
        val radius = side * 0.445f
        val safeRemaining = remainingMillis.coerceAtLeast(0L)
        val displayMinutes = if (activeProgress == null) {
            minutes.coerceAtLeast(1)
        } else {
            ((safeRemaining + 59_999L) / 60_000L).toInt().coerceAtLeast(0)
        }
        val progress = (activeProgress ?: (minutes.toFloat() / maxMinutes.toFloat()))
            .coerceIn(0f, 1f)
        val sweep = 360f * progress

        val arcColor = Color.rgb(0x75, 0xCC, 0xD6)
        val arcLight = Color.rgb(0x8A, 0xD9, 0xE1)
        val arcDeep = Color.rgb(0x63, 0xBD, 0xC9)
        val track = Color.rgb(0x1D, 0x20, 0x25)
        val tick = Color.rgb(0x6C, 0x77, 0x7C)
        val centerSurface = Color.rgb(0x0C, 0x0F, 0x14)
        val primaryText = Color.rgb(0xE8, 0xED, 0xF3)
        val secondaryText = Color.rgb(0x8E, 0x97, 0xA6)
        val lockAmber = Color.rgb(0xEB, 0xB0, 0x64)
        val lockSurface = Color.rgb(0x1D, 0x1C, 0x18)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val arcBounds = RectF(
            center - radius,
            center - radius,
            center + radius,
            center + radius
        )

        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            center,
            center,
            radius * 1.12f,
            intArrayOf(withAlpha(arcColor, 0.11f), withAlpha(centerSurface, 0.96f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.68f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(center, center, radius * 1.12f, paint)
        paint.shader = null
        paint.color = centerSurface
        canvas.drawCircle(center, center, radius * 0.79f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 12f * unit
        paint.color = track
        canvas.drawArc(arcBounds, -90f, 360f, false, paint)

        repeat(24) { index ->
            val angle = (index * 15f - 90f) * PI / 180f
            val major = index % 3 == 0
            val outerRadius = radius * 0.86f
            val innerRadius = outerRadius - (if (major) 13f else 8f) * unit
            val startX = center + cos(angle).toFloat() * innerRadius
            val startY = center + sin(angle).toFloat() * innerRadius
            val endX = center + cos(angle).toFloat() * outerRadius
            val endY = center + sin(angle).toFloat() * outerRadius
            paint.shader = null
            paint.color = withAlpha(tick, if (major) 0.72f else 0.38f)
            paint.strokeWidth = (if (major) 2.6f else 1.7f) * unit
            canvas.drawLine(startX, startY, endX, endY, paint)
        }

        if (sweep > 0f) {
            paint.color = withAlpha(arcColor, 0.08f)
            paint.strokeWidth = 34f * unit
            canvas.drawArc(arcBounds, -90f, sweep, false, paint)

            paint.color = withAlpha(arcColor, 0.16f)
            paint.strokeWidth = 23f * unit
            canvas.drawArc(arcBounds, -90f, sweep, false, paint)

            paint.shader = LinearGradient(
                center - radius,
                center - radius,
                center + radius,
                center + radius,
                intArrayOf(arcLight, arcColor, arcDeep),
                null,
                Shader.TileMode.CLAMP
            )
            paint.strokeWidth = 12f * unit
            canvas.drawArc(arcBounds, -90f, sweep, false, paint)
            paint.shader = null

            val endAngle = (sweep - 90f) * PI / 180f
            val endX = center + cos(endAngle).toFloat() * radius
            val endY = center + sin(endAngle).toFloat() * radius
            paint.style = Paint.Style.FILL
            paint.color = withAlpha(arcColor, 0.13f)
            canvas.drawCircle(endX, endY, 17f * unit, paint)
            paint.color = withAlpha(arcColor, 0.20f)
            canvas.drawCircle(endX, endY, 11f * unit, paint)
        }

        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        paint.textAlign = Paint.Align.CENTER
        paint.color = primaryText
        paint.textSize = 58f * unit
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        val numberBaseline = center - 10f * unit
        canvas.drawText(displayMinutes.toString(), center, numberBaseline, paint)

        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        paint.textSize = 11f * unit
        paint.color = secondaryText
        val label = context.getString(R.string.fg_pomodoro_clock_minutes)
        drawTrackedText(
            canvas = canvas,
            text = label,
            centerX = center,
            baseline = numberBaseline + 19f * unit,
            paint = paint,
            trackingPx = 1.8f * unit
        )

        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        val endAt = formatter.format(Date(System.currentTimeMillis() + safeRemaining))
        val endLabel = context.getString(R.string.fg_pomodoro_clock_end_at, endAt)
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        paint.textSize = 10.5f * unit
        val textWidth = paint.measureText(endLabel)
        val pillWidth = textWidth + 40f * unit
        val pillHeight = 28f * unit
        val pillLeft = center - pillWidth / 2f
        val pillTop = numberBaseline + 29f * unit
        val pill = RectF(pillLeft, pillTop, pillLeft + pillWidth, pillTop + pillHeight)

        paint.style = Paint.Style.FILL
        paint.color = lockSurface
        canvas.drawRoundRect(pill, pillHeight / 2f, pillHeight / 2f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * unit
        paint.color = withAlpha(lockAmber, 0.45f)
        canvas.drawRoundRect(pill, pillHeight / 2f, pillHeight / 2f, paint)

        val lockCenterX = pillLeft + 15f * unit
        val lockCenterY = pill.centerY()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.7f * unit
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = lockAmber
        val body = RectF(
            lockCenterX - 4.7f * unit,
            lockCenterY - 1f * unit,
            lockCenterX + 4.7f * unit,
            lockCenterY + 6f * unit
        )
        canvas.drawRoundRect(body, 1.8f * unit, 1.8f * unit, paint)
        val shackle = RectF(
            lockCenterX - 3.5f * unit,
            lockCenterY - 7f * unit,
            lockCenterX + 3.5f * unit,
            lockCenterY + 1f * unit
        )
        canvas.drawArc(shackle, 190f, 160f, false, paint)

        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        paint.textSize = 10.5f * unit
        paint.textAlign = Paint.Align.LEFT
        paint.color = lockAmber
        val metrics = paint.fontMetrics
        val baseline = pill.centerY() - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(endLabel, pillLeft + 26f * unit, baseline, paint)

        return bitmap
    }

    private fun withAlpha(color: Int, alpha: Float): Int = Color.argb(
        (255f * alpha.coerceIn(0f, 1f)).toInt(),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    private fun drawTrackedText(
        canvas: Canvas,
        text: String,
        centerX: Float,
        baseline: Float,
        paint: Paint,
        trackingPx: Float
    ) {
        if (text.isEmpty()) return
        val widths = FloatArray(text.length)
        paint.getTextWidths(text, widths)
        val totalWidth = widths.sum() + trackingPx * (text.length - 1).coerceAtLeast(0)
        var x = centerX - totalWidth / 2f
        val previousAlign = paint.textAlign
        paint.textAlign = Paint.Align.LEFT
        text.forEachIndexed { index, char ->
            canvas.drawText(char.toString(), x, baseline, paint)
            x += widths[index] + trackingPx
        }
        paint.textAlign = previousAlign
    }
}
