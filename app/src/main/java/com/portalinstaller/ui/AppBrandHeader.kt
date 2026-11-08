package com.portalinstaller.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val EMBER = Color(0xFFD9773A)
private val EMBER_LIGHT = Color(0xFFF0A65A)

@Composable
internal fun AppBrandHeader() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // The ember glyph from the launcher icon (same colours and corners).
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(
                    Brush.verticalGradient(listOf(EMBER_LIGHT, EMBER)),
                    RoundedCornerShape(topStartPercent = 27, topEndPercent = 27, bottomStartPercent = 50, bottomEndPercent = 50)
                )
        )

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "Portal",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Toolkit",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
