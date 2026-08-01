package com.portalinstaller.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Cardul standard al aplicatiei: contur subtire, fara umplere, colturi de 16dp
 * si padding interior uniform de 16dp.
 *
 * Toate sectiunile trec prin acest component ca sa nu mai existe drift de
 * forma/culoare/padding intre carduri (vezi [SectionCardShape] si
 * [SectionCardCornerRadius]). Latimea o decide containerul (LazyColumn cu
 * contentPadding orizontal), NU cardul — de aceea aici nu punem padding
 * orizontal propriu.
 */
@Composable
internal fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = SectionCardShape,
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SectionCardPadding),
            content = content
        )
    }
}

/** Raza colturilor standard, refolosita si de cardurile care nu pot folosi [SectionCard]. */
internal val SectionCardCornerRadius = 16.dp

/** Padding interior standard pentru continutul unui [SectionCard]. */
internal val SectionCardPadding = 16.dp
