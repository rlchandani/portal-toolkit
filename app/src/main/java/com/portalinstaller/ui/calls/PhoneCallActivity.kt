/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.calls

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.portalinstaller.data.calls.CallDirection
import com.portalinstaller.data.calls.PhoneCallState
import com.portalinstaller.data.calls.PhoneCalls
import com.portalinstaller.data.calls.PhonePhase

/**
 * The full-screen call: outgoing, incoming (also from the notification's full-screen intent),
 * in call and ended. Closes itself when there is nothing left to show.
 */
class PhoneCallActivity : ComponentActivity() {
    private var answerRequested by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) { setShowWhenLocked(true); setTurnScreenOn(true) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        answerRequested = intent?.getBooleanExtra(EXTRA_ANSWER, false) == true
        setContent { CallRoot(answerRequested, onAnswerHandled = { answerRequested = false }, onClose = ::finish) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_ANSWER, false)) answerRequested = true
    }

    companion object {
        private const val EXTRA_ANSWER = "answer"

        fun intent(context: Context, answer: Boolean = false): Intent = Intent(context, PhoneCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_ANSWER, answer)

        fun open(context: Context) { runCatching { context.startActivity(intent(context)) } }
    }
}

@Composable
private fun CallRoot(answerRequested: Boolean, onAnswerHandled: () -> Unit, onClose: () -> Unit) {
    val state by PhoneCalls.state.collectAsStateWithLifecycle()
    val media by PhoneCalls.media.collectAsStateWithLifecycle()
    val answer = rememberAnswer()
    LaunchedEffect(state.call == null, state.ended == null) { if (state.call == null && state.ended == null) onClose() }
    LaunchedEffect(answerRequested, state.call?.id) {
        if (answerRequested && state.call?.phase == PhonePhase.RINGING_IN) answer(false)
        if (answerRequested) onAnswerHandled()
    }
    BackHandler { if (state.call == null) PhoneCalls.dismissEnded() }
    Box(Modifier.fillMaxSize().background(CallColors.VideoBg)) { Screen(state, media, answer) }
}

@Composable
private fun Screen(state: PhoneCallState, media: com.portalinstaller.data.calls.PhoneCallMedia?, answer: (Boolean) -> Unit) {
    val call = state.call
    val ended = state.ended
    when {
        call == null -> ended?.let { EndedCallScreen(it) }
        call.phase == PhonePhase.RINGING_IN -> IncomingCallScreen(call, answer)
        call.direction == CallDirection.OUTGOING && call.phase.beforeAnswer -> OutgoingCallScreen(call)
        else -> InCallScreen(call, media)
    }
}

/**
 * Answer, asking for the mic (and the camera for video) first. Without the mic the call can't
 * go ahead, so it's declined; without the camera it carries on as audio.
 */
@Composable
internal fun rememberAnswer(): (Boolean) -> Unit {
    val context = LocalContext.current
    var audioOnly by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val mic = granted[Manifest.permission.RECORD_AUDIO] ?: has(context, Manifest.permission.RECORD_AUDIO)
        val camera = granted[Manifest.permission.CAMERA] ?: has(context, Manifest.permission.CAMERA)
        if (mic) PhoneCalls.answer(audioOnly || !camera) else PhoneCalls.decline()
    }
    return { only ->
        audioOnly = only
        val wanted = callPermissions(video = !only).filterNot { has(context, it) }
        if (wanted.isEmpty()) PhoneCalls.answer(only) else launcher.launch(wanted.toTypedArray())
    }
}

internal fun callPermissions(video: Boolean): List<String> =
    listOfNotNull(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA.takeIf { video })

internal fun has(context: Context, permission: String) =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
