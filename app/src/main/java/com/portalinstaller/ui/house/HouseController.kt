/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.house

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.portalinstaller.data.house.HouseAct
import com.portalinstaller.data.house.HouseActResult
import com.portalinstaller.data.house.HouseConfirm
import com.portalinstaller.data.house.HouseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** A confirm the Portal asked for, with the call to resend once the household answers. */
internal data class HousePendingConfirm(val confirm: HouseConfirm, val act: HouseAct)

/**
 * Sends controls and runs the confirm flow: an asking call comes back with the Portal's ticket,
 * the sheet asks (biometric, dialog or PIN pad), and the same call goes again with the ticket.
 * Every finished call wakes the poll loop so the Portal's new state shows at once.
 */
@Stable
internal class HouseController(val repository: HouseRepository, private val scope: CoroutineScope) {
    var confirm by mutableStateOf<HousePendingConfirm?>(null)
        private set
    /** Conflated: many taps make one early poll. */
    val wake = Channel<Unit>(Channel.CONFLATED)

    fun send(act: HouseAct) { scope.launch { handle(repository.act(act)) } }

    fun sendAll(acts: List<HouseAct>, label: String) {
        scope.launch {
            for (a in acts) {
                val r = repository.act(a)
                if (r !is HouseActResult.Done) { handle(r); return@launch }
            }
            repository.notify(label); wake.trySend(Unit)
        }
    }

    fun confirmed(code: String?) {
        val p = confirm ?: return
        confirm = null
        scope.launch { handle(repository.act(p.act, p.confirm.ticket, code)) }
    }

    fun cancelConfirm() { confirm = null }

    private fun handle(r: HouseActResult) {
        when (r) {
            is HouseActResult.Done -> { repository.notify(r.label); wake.trySend(Unit) }
            is HouseActResult.Confirm -> confirm = HousePendingConfirm(r.confirm, r.act)
            is HouseActResult.Failed -> { repository.notify(r.text, error = true); wake.trySend(Unit) }
        }
    }
}
