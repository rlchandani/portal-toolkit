/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Retained by the ViewModel; contains no device credentials or Android/Compose lifecycle objects. */
data class RemoteTextState(
    val deviceKey: String = "",
    val connected: Boolean = false,
    val keyboardOpen: Boolean = false,
    val draft: String = "",
    val sending: Boolean = false,
    val error: Boolean = false,
) {
    override fun toString() = "RemoteTextState(deviceKey=$deviceKey, connected=$connected, " +
        "keyboardOpen=$keyboardOpen, draft=redacted, sending=$sending, error=$error)"
}

/**
 * One session owns the draft and acknowledgement. [scope] must outlive the composition (the
 * production owner supplies viewModelScope). [sendConfirmed] must check the live session before
 * capturing a repository ticket; the device key intentionally contains only identity/generation.
 */
class RemoteTextController(
    private val scope: CoroutineScope,
    private val sendConfirmed: suspend (deviceKey: String, text: String) -> Boolean,
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow(RemoteTextState())
    val state: StateFlow<RemoteTextState> = mutableState.asStateFlow()
    private var sessionEpoch = 0L
    private var draftRevision = 0L
    private var nextSendId = 0L
    private var activeSendId: Long? = null

    /** Called by the ViewModel's repository observer, never by an outgoing UI composition. */
    fun updateSession(deviceKey: String, connected: Boolean) = synchronized(lock) {
        val current = mutableState.value
        if (current.deviceKey != deviceKey) {
            sessionEpoch++
            draftRevision = 0L
            activeSendId = null
            mutableState.value = RemoteTextState(deviceKey = deviceKey, connected = connected)
        } else {
            mutableState.value = current.copy(connected = connected,
                keyboardOpen = current.keyboardOpen && connected)
        }
    }

    fun open(deviceKey: String) = synchronized(lock) {
        val current = mutableState.value
        if (current.deviceKey == deviceKey && current.connected)
            mutableState.value = current.copy(keyboardOpen = true, error = false)
    }

    fun dismiss(deviceKey: String) = synchronized(lock) {
        val current = mutableState.value
        if (current.deviceKey == deviceKey) mutableState.value = current.copy(keyboardOpen = false)
    }

    fun edit(deviceKey: String, draft: String) = synchronized(lock) {
        val current = mutableState.value
        if (current.deviceKey == deviceKey) {
            draftRevision++
            mutableState.value = current.copy(draft = draft, error = false)
        }
    }

    private class Pending(val id: Long, val epoch: Long, val revision: Long, val deviceKey: String, val text: String)

    /** Marks pending synchronously, so duplicate button/IME callbacks cannot enqueue two sends. */
    fun send(deviceKey: String): Job? {
        val pending = synchronized(lock) {
            val current = mutableState.value
            if (current.deviceKey != deviceKey || !current.connected || !current.keyboardOpen ||
                current.sending || current.draft.isBlank()) return null
            val id = ++nextSendId
            activeSendId = id
            mutableState.value = current.copy(sending = true, error = false)
            Pending(id, sessionEpoch, draftRevision, deviceKey, current.draft)
        }
        // Begin ticket capture before returning to another UI event; still respect owner cancellation.
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var confirmed = false
            try {
                currentCoroutineContext().ensureActive()
                confirmed = sendConfirmed(pending.deviceKey, pending.text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Transport failures retain the draft. Never surface raw text or exception details.
            } finally {
                synchronized(lock) {
                    if (sessionEpoch == pending.epoch && activeSendId == pending.id) {
                        activeSendId = null
                        val current = mutableState.value
                        val unchangedDraft = draftRevision == pending.revision
                        mutableState.value = current.copy(
                            sending = false,
                            error = !confirmed && unchangedDraft,
                            draft = if (confirmed && unchangedDraft) "" else current.draft,
                            keyboardOpen = if (confirmed && unchangedDraft) false else current.keyboardOpen,
                        )
                    }
                }
            }
        }
    }
}
