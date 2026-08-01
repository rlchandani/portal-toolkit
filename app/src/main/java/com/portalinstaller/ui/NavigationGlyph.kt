package com.portalinstaller.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
internal fun NavigationGlyph(index: Int, selected: Boolean) {
    val color = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Canvas(modifier = Modifier.size(24.dp)) {
        val strokeWidth = 1.8.dp.toPx()
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        val inset = 3.dp.toPx()

        when (index) {
            0 -> {
                val cellSize = 7.dp.toPx()
                val gap = 4.dp.toPx()
                listOf(
                    Offset(inset, inset),
                    Offset(inset + cellSize + gap, inset),
                    Offset(inset, inset + cellSize + gap),
                    Offset(inset + cellSize + gap, inset + cellSize + gap)
                ).forEach { topLeft ->
                    drawRoundRect(
                        color = color,
                        topLeft = topLeft,
                        size = Size(cellSize, cellSize),
                        cornerRadius = CornerRadius(1.5.dp.toPx()),
                        style = stroke
                    )
                }
            }

            1 -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(inset, 4.dp.toPx()),
                    size = Size(18.dp.toPx(), 16.dp.toPx()),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    style = stroke
                )
                drawCircle(
                    color = color,
                    radius = 1.5.dp.toPx(),
                    center = Offset(17.dp.toPx(), 8.dp.toPx())
                )
                drawLine(
                    color = color,
                    start = Offset(5.dp.toPx(), 17.dp.toPx()),
                    end = Offset(10.dp.toPx(), 12.dp.toPx()),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = color,
                    start = Offset(10.dp.toPx(), 12.dp.toPx()),
                    end = Offset(18.dp.toPx(), 19.dp.toPx()),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
            }

            2 -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(3.dp.toPx(), 5.dp.toPx()),
                    size = Size(13.dp.toPx(), 15.dp.toPx()),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    style = stroke
                )
                drawRoundRect(
                    color = color,
                    topLeft = Offset(11.dp.toPx(), 3.dp.toPx()),
                    size = Size(10.dp.toPx(), 13.dp.toPx()),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    style = stroke
                )
            }

            else -> {
                listOf(6.dp, 12.dp, 18.dp).forEach { x ->
                    drawCircle(
                        color = color,
                        radius = 1.8.dp.toPx(),
                        center = Offset(x.toPx(), 12.dp.toPx())
                    )
                }
            }
        }
    }
}
