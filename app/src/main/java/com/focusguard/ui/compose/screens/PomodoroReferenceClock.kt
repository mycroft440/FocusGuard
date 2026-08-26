package com.focusguard.ui.compose.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusguard.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pomodoro clock styled after the approved focus-mode reference.
 *
 * This component intentionally owns only the visual clock and the same radial duration gesture
 * that the previous Pomodoro dial exposed. Pomodoro plan/session behavior remains outside it.
 */
@Composable
internal fun PomodoroReferenceClock(
    minutes: Int,
    maxMinutes: Int,
    activeProgress: Float?,
    remainingMillis: Long,
    onMinutesChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val interactive = activeProgress == null
    val safeRemainingMillis = remainingMillis.coerceAtLeast(0L)
    val displayMinutes = if (interactive) {
        minutes.coerceAtLeast(1)
    } else {
        ((safeRemainingMillis + 59_999L) / 60_000L).toInt().coerceAtLeast(0)
    }
    val endAt = remember(safeRemainingMillis, displayMinutes) {
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        formatter.format(Date(System.currentTimeMillis() + safeRemainingMillis))
    }

    // Cores amostradas do print de referência, pixel a pixel, e não escolhidas
    // no olho: o arco, a pílula "pronto para focar" e os controles + / - de lá
    // são todos o mesmo #75CCD6.
    val arcColor = Color(0xFF75CCD6)
    val arcLight = Color(0xFF8AD9E1)
    val arcDeep = Color(0xFF63BDC9)
    val track = Color(0xFF1D2025)
    // O traço do marcador é desenhado com alfa (0.72 no maior, 0.38 no menor);
    // este tom é o que, depois do alfa, cai no #515A5F medido no print.
    val tick = Color(0xFF6C777C)
    val centerSurface = Color(0xFF0C0F14)
    val primaryText = Color(0xFFE8EDF3)
    val secondaryText = Color(0xFF8E97A6)
    // O cadeado continua âmbar: no print ele é a única coisa que não é ciano,
    // e é isso que faz "volta às 19:02" se ler como um aviso, e não como mais
    // um número do relógio.
    val lockAmber = Color(0xFFEBB064)
    val lockSurface = Color(0xFF1D1C18)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(interactive, maxMinutes) {
                    if (!interactive) return@pointerInput
                    detectDragGestures(
                        onDragStart = { position ->
                            onMinutesChange(
                                referenceMinutesFromPosition(
                                    position = position,
                                    width = size.width,
                                    height = size.height,
                                    maxMinutes = maxMinutes
                                )
                            )
                        },
                        onDrag = { change, _ ->
                            onMinutesChange(
                                referenceMinutesFromPosition(
                                    position = change.position,
                                    width = size.width,
                                    height = size.height,
                                    maxMinutes = maxMinutes
                                )
                            )
                        }
                    )
                }
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension * 0.445f
            val trackWidth = 12.dp.toPx()
            val progress = (activeProgress ?: (minutes.toFloat() / maxMinutes.toFloat()))
                .coerceIn(0f, 1f)
            val sweep = 360f * progress
            val arcTopLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2f, radius * 2f)

            // Halo central e brilho externo, na cor do arco.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        arcColor.copy(alpha = 0.11f),
                        centerSurface.copy(alpha = 0.96f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = radius * 1.12f
                ),
                radius = radius * 1.12f,
                center = center
            )
            drawCircle(centerSurface, radius * 0.79f, center)

            // Inactive circular track.
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = trackWidth, cap = StrokeCap.Round)
            )

            // Minute/hour-like marks placed just inside the ring.
            repeat(24) { index ->
                val angle = (index * 15f - 90f) * PI / 180f
                val major = index % 3 == 0
                val outerRadius = radius * 0.86f
                val innerRadius = outerRadius - if (major) 13.dp.toPx() else 8.dp.toPx()
                val start = Offset(
                    center.x + cos(angle).toFloat() * innerRadius,
                    center.y + sin(angle).toFloat() * innerRadius
                )
                val end = Offset(
                    center.x + cos(angle).toFloat() * outerRadius,
                    center.y + sin(angle).toFloat() * outerRadius
                )
                drawLine(
                    color = tick.copy(alpha = if (major) 0.72f else 0.38f),
                    start = start,
                    end = end,
                    strokeWidth = if (major) 2.6.dp.toPx() else 1.7.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Brilho em várias passadas atrás do arco ativo.
            if (sweep > 0f) {
                drawArc(
                    color = arcColor.copy(alpha = 0.08f),
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = 34.dp.toPx(), cap = StrokeCap.Round)
                )
                drawArc(
                    color = arcColor.copy(alpha = 0.16f),
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = 23.dp.toPx(), cap = StrokeCap.Round)
                )
                drawArc(
                    brush = Brush.linearGradient(
                        colors = listOf(arcLight, arcColor, arcDeep),
                        start = Offset(center.x - radius, center.y - radius),
                        end = Offset(center.x + radius, center.y + radius)
                    ),
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = trackWidth, cap = StrokeCap.Round)
                )

                // A ponta do arco só ganha um adensamento do próprio brilho.
                // O ponto branco que ficava aqui não existe no print: lá o arco
                // termina na própria tampa arredondada.
                val endAngle = (sweep - 90f) * PI / 180f
                val knobCenter = Offset(
                    center.x + cos(endAngle).toFloat() * radius,
                    center.y + sin(endAngle).toFloat() * radius
                )
                drawCircle(arcColor.copy(alpha = 0.13f), 17.dp.toPx(), knobCenter)
                drawCircle(arcColor.copy(alpha = 0.20f), 11.dp.toPx(), knobCenter)
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(top = 12.dp)
        ) {
            Text(
                text = displayMinutes.toString(),
                color = primaryText,
                fontSize = 58.sp,
                lineHeight = 60.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.fg_pomodoro_clock_minutes),
                color = secondaryText,
                fontSize = 11.sp,
                letterSpacing = 3.2.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.size(10.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(lockSurface)
                    .border(
                        width = 1.dp,
                        color = lockAmber.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(50)
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = lockAmber,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = stringResource(R.string.fg_pomodoro_clock_end_at, endAt),
                    color = lockAmber,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

private fun referenceMinutesFromPosition(
    position: Offset,
    width: Int,
    height: Int,
    maxMinutes: Int
): Int {
    val centerX = width / 2f
    val centerY = height / 2f
    var angle = atan2(position.y - centerY, position.x - centerX) *
        (180f / PI.toFloat()) + 90f
    if (angle < 0f) angle += 360f
    return ((angle / 360f) * maxMinutes)
        .roundToInt()
        .coerceIn(1, maxMinutes)
}
