package com.focusguard.ui.compose.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.consume
import androidx.compose.ui.input.pointer.pointerInput
import com.focusguard.security.PasswordAppUnlockStore
import com.focusguard.ui.compose.theme.AccentCyan
import com.focusguard.ui.compose.theme.TextHint
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Grade 3x3 no estilo padrão de desbloqueio do Android.
 *
 * Quando [hideTrace] é verdadeiro, o gesto continua funcionando normalmente,
 * mas nem a linha nem os pontos já percorridos são revelados na tela.
 */
@Composable
fun PatternLockInput(
    modifier: Modifier = Modifier,
    hideTrace: Boolean,
    enabled: Boolean = true,
    resetKey: Int = 0,
    onPatternComplete: (String) -> Unit
) {
    val selected = remember { mutableStateListOf<Int>() }
    var pointerPosition by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(resetKey) {
        selected.clear()
        pointerPosition = null
    }

    fun addNode(node: Int) {
        if (node !in 0..8 || node in selected) return
        val previous = selected.lastOrNull()
        if (previous != null) {
            intermediateNode(previous, node)?.let { middle ->
                if (middle !in selected) selected.add(middle)
            }
        }
        selected.add(node)
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .pointerInput(enabled, resetKey) {
                if (!enabled) return@pointerInput

                fun nodeAt(position: Offset): Int? {
                    val cellWidth = size.width / 3f
                    val cellHeight = size.height / 3f
                    val threshold = min(cellWidth, cellHeight) * 0.34f
                    for (row in 0..2) {
                        for (column in 0..2) {
                            val center = Offset(
                                x = (column + 0.5f) * cellWidth,
                                y = (row + 0.5f) * cellHeight
                            )
                            val dx = position.x - center.x
                            val dy = position.y - center.y
                            if (sqrt(dx * dx + dy * dy) <= threshold) {
                                return@nodeAt row * 3 + column
                            }
                        }
                    }
                    return@nodeAt null
                }

                detectDragGestures(
                    onDragStart = { offset ->
                        selected.clear()
                        pointerPosition = offset
                        nodeAt(offset)?.let(::addNode)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        pointerPosition = change.position
                        nodeAt(change.position)?.let(::addNode)
                    },
                    onDragCancel = {
                        selected.clear()
                        pointerPosition = null
                    },
                    onDragEnd = {
                        pointerPosition = null
                        if (selected.isNotEmpty()) {
                            onPatternComplete(
                                PasswordAppUnlockStore.encodePattern(selected.toList())
                            )
                        }
                    }
                )
            }
    ) {
        val cellWidth = size.width / 3f
        val cellHeight = size.height / 3f
        val centers = List(9) { index ->
            val row = index / 3
            val column = index % 3
            Offset(
                x = (column + 0.5f) * cellWidth,
                y = (row + 0.5f) * cellHeight
            )
        }
        val baseRadius = min(cellWidth, cellHeight) * 0.085f

        if (!hideTrace && selected.isNotEmpty()) {
            val path = Path().apply {
                val first = centers[selected.first()]
                moveTo(first.x, first.y)
                selected.drop(1).forEach { node ->
                    val point = centers[node]
                    lineTo(point.x, point.y)
                }
                pointerPosition?.let { point -> lineTo(point.x, point.y) }
            }
            drawPath(
                path = path,
                color = AccentCyan,
                style = Stroke(width = baseRadius * 0.72f)
            )
        }

        centers.forEachIndexed { index, center ->
            val selectedVisible = !hideTrace && index in selected
            drawCircle(
                color = if (selectedVisible) AccentCyan else TextHint,
                radius = if (selectedVisible) baseRadius * 1.32f else baseRadius,
                center = center
            )
            if (selectedVisible) {
                drawCircle(
                    color = AccentCyan.copy(alpha = 0.22f),
                    radius = baseRadius * 2.1f,
                    center = center,
                    style = Stroke(width = baseRadius * 0.35f)
                )
            }
        }
    }
}

private fun intermediateNode(from: Int, to: Int): Int? {
    val fromRow = from / 3
    val fromColumn = from % 3
    val toRow = to / 3
    val toColumn = to % 3
    val rowDistance = kotlin.math.abs(fromRow - toRow)
    val columnDistance = kotlin.math.abs(fromColumn - toColumn)

    val hasMiddle = (rowDistance == 2 && columnDistance == 0) ||
        (rowDistance == 0 && columnDistance == 2) ||
        (rowDistance == 2 && columnDistance == 2)
    if (!hasMiddle) return null

    val middleRow = (fromRow + toRow) / 2
    val middleColumn = (fromColumn + toColumn) / 2
    return middleRow * 3 + middleColumn
}
