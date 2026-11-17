/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.chores

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.portalinstaller.data.chores.Chore
import com.portalinstaller.data.chores.ChoreBoard
import com.portalinstaller.data.chores.ChoreCopy
import com.portalinstaller.data.chores.ChoreKid
import com.portalinstaller.data.chores.ChoreState
import com.portalinstaller.data.chores.ChoreTick
import com.portalinstaller.data.chores.TickStatus
import com.portalinstaller.ui.controlcenter.GroupCard
import com.portalinstaller.ui.routines.RoutinePicture

/** Ticks waiting for a parent: OK awards the stars, "Not this time" frees the chore to try again. */
@Composable
internal fun PendingCard(board: ChoreState, pending: List<ChoreTick>, today: Int, enabled: Boolean,
                         onApprove: (ChoreTick) -> Unit, onDecline: (ChoreTick) -> Unit) {
    GroupCard {
        Text("Waiting for your OK", style = MaterialTheme.typography.titleMedium)
        pending.forEach { t ->
            val kid = board.kids[t.kid]
            val chore = board.chores[t.chore]
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                chore?.let { RoutinePicture(it.pic, ChoreUi.Picture) }
                Column(Modifier.weight(1f)) {
                    Text(ChoreCopy.pendingLine(kid?.name ?: "Someone", chore?.title ?: "A chore", t.day, today),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("★".repeat(t.stars), color = ChoreUi.Star)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onApprove(t) }, enabled = enabled, modifier = Modifier.heightIn(min = ChoreUi.MinTouch)) { Text("OK") }
                OutlinedButton(onClick = { onDecline(t) }, enabled = enabled, modifier = Modifier.heightIn(min = ChoreUi.MinTouch)) {
                    Text("Not this time")
                }
            }
        }
    }
}

/** Each kid's stars this week toward their goal, and the last four weeks. */
@Composable
internal fun WeekCard(board: ChoreState, today: Int, onEditKid: (ChoreKid) -> Unit) {
    val weeks = ChoreBoard.week(board, today).associateBy { it.kid }
    val history = ChoreBoard.history(board, today)
    GroupCard {
        Text("This week", style = MaterialTheme.typography.titleMedium)
        board.liveKids.forEach { kid ->
            val week = weeks[kid.id] ?: return@forEach
            Column(Modifier.fillMaxWidth().clickable { onEditKid(kid) }.padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    KidAvatar(kid.avatar, kid.color)
                    Column(Modifier.weight(1f)) {
                        Text(kid.name, fontWeight = FontWeight.SemiBold)
                        Text(ChoreBoard.goalLine(week) + if (week.pending > 0) " · ${week.pending} waiting" else "",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (week.reached) Text("Goal!", color = ChoreUi.Done, fontWeight = FontWeight.SemiBold)
                }
                GoalBar(week)
                val past = history.mapNotNull { w -> w.firstOrNull { it.kid == kid.id } }
                if (past.any { it.stars > 0 }) Text("Last 4 weeks: " + past.joinToString(" · ") { "${it.stars}★" + if (it.reached) " ✓" else "" },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("Stars start fresh every Monday.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Chores ticked today, with "Take back" for one that wasn't really done. */
@Composable
internal fun DoneTodayCard(board: ChoreState, today: Int, enabled: Boolean, onTakeBack: (ChoreTick) -> Unit) {
    val done = ChoreBoard.holders(board).values.filter { it.day == today && it.status != TickStatus.PENDING }
        .sortedBy { board.kids[it.kid]?.name.orEmpty() }
    if (done.isEmpty()) return
    GroupCard {
        Text("Done today", style = MaterialTheme.typography.titleMedium)
        done.forEach { t ->
            val chore = board.chores[t.chore]
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                chore?.let { RoutinePicture(it.pic, ChoreUi.Picture) }
                Text("${board.kids[t.kid]?.name ?: "Someone"} · ${chore?.title ?: "A chore"} · ${"★".repeat(t.stars)}",
                    Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { onTakeBack(t) }, enabled = enabled, modifier = Modifier.heightIn(min = ChoreUi.MinTouch)) {
                    Text("Take back")
                }
            }
        }
    }
}

@Composable
internal fun KidsCard(kids: List<ChoreKid>, canAdd: Boolean, onEdit: (ChoreKid) -> Unit, onAdd: () -> Unit) {
    GroupCard {
        Text("Kids", style = MaterialTheme.typography.titleMedium)
        if (kids.isEmpty()) Text("Add your kids first, then give them chores.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        kids.forEach { kid ->
            Row(Modifier.fillMaxWidth().heightIn(min = ChoreUi.MinTouch).clickable { onEdit(kid) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KidAvatar(kid.avatar, kid.color)
                Column(Modifier.weight(1f)) {
                    Text(kid.name, fontWeight = FontWeight.Medium)
                    Text(if (kid.goal > 0) "Goal ${kid.goal} ★" + (if (kid.reward.isNotBlank()) " — ${kid.reward}" else "") else "No weekly goal",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Edit", color = MaterialTheme.colorScheme.primary)
            }
        }
        if (canAdd) OutlinedButton(onClick = onAdd, modifier = Modifier.heightIn(min = ChoreUi.MinTouch)) { Text("Add a kid") }
    }
}

@Composable
internal fun ChoreListCard(board: ChoreState, today: Int, canAdd: Boolean, onEdit: (Chore) -> Unit, onAdd: () -> Unit) {
    GroupCard {
        Text("Chores", style = MaterialTheme.typography.titleMedium)
        if (board.liveChores.isEmpty()) Text("No chores yet. Pick a picture, a kid and how many stars.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        board.liveChores.forEach { c ->
            Row(Modifier.fillMaxWidth().heightIn(min = ChoreUi.MinTouch).clickable { onEdit(c) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RoutinePicture(c.pic, ChoreUi.Picture)
                Column(Modifier.weight(1f)) {
                    Text(c.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val who = c.kid?.let { board.kids[it]?.name } ?: "Anyone"
                    Text("$who · ${ChoreCopy.schedule(c.schedule, today)}" + if (c.needsOk) " · Needs your OK" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("★".repeat(c.stars), color = ChoreUi.Star)
            }
        }
        if (canAdd) OutlinedButton(onClick = onAdd, modifier = Modifier.heightIn(min = ChoreUi.MinTouch)) { Text("Add a chore") }
    }
}
