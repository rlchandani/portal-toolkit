/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.house

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portalinstaller.data.house.HousePerson

/** The phone's Control Center screens (the mock's chips under the title). */
internal enum class HouseView { MAP, TESLA, ALERTS, SECURITY, CAMERAS }

/** One chip: which screen, its words ("Alerts · 2"). */
internal data class HouseChip(val view: HouseView, val label: String)

private val AVATAR_COLORS = listOf(0xFF8FBCFF, 0xFF7FD6B0, 0xFFFFC38A, 0xFFE5A1FF, 0xFFFF9EA8).map { Color(it) }

/** Serif title ("Home", "Security"), who's home as initials, and the screen chips. */
@Composable
internal fun HouseHeader(title: String, people: List<HousePerson>, chips: List<HouseChip>, current: HouseView, onChip: (HouseView) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontFamily = FontFamily.Serif, fontSize = 26.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Avatars(people.filter { it.home })
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            chips.forEach { c ->
                FilterChip(selected = c.view == current, onClick = { onChip(c.view) }, label = { Text(c.label) },
                    shape = HouseShapes.pill, colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary))
            }
        }
    }
}

@Composable
private fun Avatars(home: List<HousePerson>) {
    if (home.isEmpty()) return
    val names = home.joinToString { it.name }
    Box(Modifier.semantics { contentDescription = "Home: $names" }) {
        home.take(MAX_AVATARS).forEachIndexed { i, p ->
            Box(Modifier.offset(x = (i * AVATAR_STEP).dp).size(28.dp).background(AVATAR_COLORS[i % AVATAR_COLORS.size], CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.background, CircleShape), contentAlignment = Alignment.Center) {
                Text(p.name.take(1).uppercase(), color = Color(0xFF14161B), fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
            }
        }
        Box(Modifier.size(width = (28 + (home.take(MAX_AVATARS).size - 1) * AVATAR_STEP).dp, height = 28.dp))
    }
}

/** A tinted strip over stale or unreachable values ("Reconnecting… values from 7:36 pm"). */
@Composable
internal fun HouseBanner(text: String, danger: Boolean) {
    val c = houseColors
    Text(text, Modifier.fillMaxWidth().background(if (danger) c.redBg else c.amberBg, HouseShapes.card).padding(14.dp),
        color = if (danger) c.red else c.amber, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
}

/** The mock's section label (`.psec`): small caps over a card. */
@Composable
internal fun HouseSection(text: String) {
    Text(text.uppercase(), Modifier.padding(start = 4.dp, top = 14.dp, bottom = 8.dp), fontSize = 11.5.sp,
        fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A rounded surface card (`.pcard`). */
@Composable
internal fun HouseCard(modifier: Modifier = Modifier, padding: Int = 14, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, HouseShapes.card).padding(padding.dp)) { content() }
}

private const val MAX_AVATARS = 4
private const val AVATAR_STEP = 20
