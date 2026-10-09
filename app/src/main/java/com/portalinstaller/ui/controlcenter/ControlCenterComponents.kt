/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }

/** A page item needs its own spacing; LazyColumn spaces items, not their children. */
@Composable
internal fun PageSections(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        content()
    }
}

@Composable
internal fun PageTitle(title: String, detail: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        if (!detail.isNullOrBlank()) {
            Text(detail, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 8.dp, bottom = 3.dp))
}

@Composable
internal fun GroupCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
        }
    }
}

@Composable
internal fun ActionRow(
    title: String,
    detail: String = "",
    glyph: String = "settings",
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(Modifier.clickable(enabled = enabled, onClick = onClick).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ControlGlyph(glyph, if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        Text("›", style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun EmptyMessage(title: String, detail: String) {
    GroupCard {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        Text(detail, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ControlGlyph(
    name: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier.size(25.dp),
) {
    Canvas(modifier) {
        val u = size.minDimension / 24f
        val stroke = Stroke(1.65f * u)
        fun point(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, point(x1, y1), point(x2, y2), 1.65f * u)
        fun rect(x: Float, y: Float, w: Float, h: Float) =
            drawRect(tint, point(x, y), Size(w * u, h * u), style = stroke)
        when (name) {
            "home" -> {
                val roof = Path().apply { moveTo(3*u, 10*u); lineTo(12*u, 3*u); lineTo(21*u, 10*u) }
                drawPath(roof, tint, style = stroke)
                line(5f,9f,5f,21f); line(5f,21f,19f,21f); line(19f,21f,19f,9f)
                rect(10f,14f,4f,7f)
            }
            "remote" -> {
                drawRoundRect(tint, point(6f,2f), Size(12*u,20*u),
                    androidx.compose.ui.geometry.CornerRadius(3*u), style = stroke)
                drawCircle(tint, 3*u, point(12f,11f), style = stroke)
                line(12f,7f,12f,15f); line(8f,11f,16f,11f)
                drawCircle(tint, u, point(12f,18f))
            }
            "photos" -> {
                rect(3f,4f,18f,16f)
                drawCircle(tint,1.6f*u,point(8f,9f),style=stroke)
                val mountain = Path().apply {
                    moveTo(4*u,18*u); lineTo(10*u,12*u); lineTo(14*u,16*u)
                    lineTo(17*u,12*u); lineTo(21*u,17*u)
                }
                drawPath(mountain,tint,style=stroke)
            }
            "apps", "layout" -> {
                rect(3f,3f,7f,7f);rect(14f,3f,7f,7f)
                rect(3f,14f,7f,7f);rect(14f,14f,7f,7f)
            }
            "settings" -> {
                for (y in listOf(6f,12f,18f)) line(3f,y,21f,y)
                drawCircle(tint,2*u,point(8f,6f),style=stroke)
                drawCircle(tint,2*u,point(16f,12f),style=stroke)
                drawCircle(tint,2*u,point(10f,18f),style=stroke)
            }
            "backup" -> {
                rect(4f,5f,16f,15f);line(4f,10f,20f,10f);line(9f,14f,15f,14f)
                line(7f,2f,17f,2f)
            }
            "scenes" -> {
                val bolt = Path().apply {
                    moveTo(13*u,2*u); lineTo(5*u,13*u); lineTo(11*u,13*u)
                    lineTo(10*u,22*u); lineTo(19*u,10*u); lineTo(13*u,10*u); close()
                }
                drawPath(bolt,tint,style=stroke)
            }
            "jarvis" -> {
                drawRoundRect(tint,point(9f,3f),Size(6*u,11*u),
                    androidx.compose.ui.geometry.CornerRadius(3*u),style=stroke)
                drawArc(tint,0f,180f,false,point(6f,8f),Size(12*u,10*u),style=stroke)
                line(12f,18f,12f,22f);line(9f,22f,15f,22f)
            }
            "refresh" -> {
                drawArc(tint,35f,290f,false,point(4f,4f),Size(16*u,16*u),style=stroke)
                line(20f,4f,20f,10f);line(15f,10f,20f,10f)
            }
            "chevron" -> { line(8f,5f,15f,12f); line(15f,12f,8f,19f) }
            else -> {
                drawCircle(tint,8*u,point(12f,12f),style=stroke)
                drawCircle(tint,1.5f*u,point(12f,12f))
            }
        }
    }
}
