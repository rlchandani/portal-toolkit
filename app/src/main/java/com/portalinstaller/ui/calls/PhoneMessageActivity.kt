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
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.portalinstaller.data.calls.ComposeEffect
import com.portalinstaller.data.calls.ComposeEvent
import com.portalinstaller.data.calls.ComposeState
import com.portalinstaller.data.calls.PhoneCalls
import com.portalinstaller.data.calls.PhoneCompose
import com.portalinstaller.data.calls.PhoneMessageCrypto
import com.portalinstaller.data.calls.PhoneMessageRecorder
import com.portalinstaller.data.calls.PhoneMessageStore
import com.portalinstaller.data.calls.PhoneMessages
import com.portalinstaller.data.calls.PortalRef
import kotlinx.coroutines.launch

/**
 * Calling 2b on the phone: "Leave a message" for a Portal (Ready → Recording → Review → Send),
 * and watching a message a Portal left. Not exported. Camera and mic are open only while this
 * screen is started; leaving it (Back, Home, screen off) discards the take and closes everything.
 * Playback decrypts chunk by chunk in memory: no plaintext file is ever written for a message.
 */
class PhoneMessageActivity : ComponentActivity() {
    private var composer: PhoneMessageComposer? = null
    private var player: MessagePlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        PhoneMessageStore.load(this)
        val watch = intent.getStringExtra(EXTRA_MESSAGE)
        if (watch != null) {
            val m = PhoneMessageStore.messages.value.firstOrNull { it.id == watch } ?: return finish()
            PhoneMessageStore.markSeen(this, m.id)
            val p = MessagePlayer { PhoneMessageStore.reader(this, m)?.let(::SealedSource) }
            player = p
            setContent { MessageWatchScreen(m, p, onClose = ::finish, onDelete = { PhoneMessageStore.delete(this, m.id); finish() }) }
            return
        }
        val portal = PortalRef(intent.getStringExtra(EXTRA_SESSION) ?: return finish(), intent.getStringExtra(EXTRA_PORTAL) ?: return finish(),
            intent.getStringExtra(EXTRA_NAME) ?: return finish())
        val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filterNot { has(this, it) }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), PERMISSIONS)
        val c = PhoneMessageComposer(this, portal, onFinish = ::finish)
        composer = c
        setContent { MessageComposeScreen(c) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        composer?.let { if (it.state.cameraOn) it.recorder.camera(true) }
    }

    override fun onStop() {
        // Privacy: off screen means nothing is kept; camera and mic close now.
        if (!isChangingConfigurations) { composer?.release(); composer = null; player?.release(); player = null; finish() }
        super.onStop()
    }

    override fun onDestroy() {
        composer?.release(); composer = null
        player?.release(); player = null
        super.onDestroy()
    }

    companion object {
        private const val PERMISSIONS = 7411
        private const val EXTRA_MESSAGE = "message"
        private const val EXTRA_SESSION = "session"
        private const val EXTRA_PORTAL = "portal"
        private const val EXTRA_NAME = "name"

        fun compose(context: Context, portal: PortalRef) {
            runCatching {
                context.startActivity(Intent(context, PhoneMessageActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_SESSION, portal.sessionId).putExtra(EXTRA_PORTAL, portal.portalId).putExtra(EXTRA_NAME, portal.name))
            }
        }

        fun watch(context: Context, id: String): Intent =
            Intent(context, PhoneMessageActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(EXTRA_MESSAGE, id)
    }
}

/** Runs [PhoneCompose] against the recorder and the uploader; state for the screen. */
internal class PhoneMessageComposer(private val activity: PhoneMessageActivity, val portal: PortalRef, private val onFinish: () -> Unit) {
    var state by mutableStateOf(ComposeState())
        private set
    val recorder = PhoneMessageRecorder(activity) { ok, ms -> main.post { dispatch(ComposeEvent.Recorded(ok, ms)) } }
    /** Review: "Watch it" plays our own take (the only plaintext, in private temp). */
    val review = MessagePlayer { runCatching { PlainSource(recorder.take) }.getOrNull() }
    private val main = Handler(Looper.getMainLooper())
    private var released = false
    private val tick = object : Runnable {
        override fun run() { dispatch(ComposeEvent.Tick(System.currentTimeMillis())); if (!released) main.postDelayed(this, TICK_MS) }
    }

    init {
        recorder.camera(true)
        main.postDelayed(tick, TICK_MS)
    }

    fun dispatch(e: ComposeEvent) {
        if (released) return
        val step = PhoneCompose.reduce(state, e)
        state = step.state
        step.effects.forEach(::perform)
    }

    private fun perform(effect: ComposeEffect) {
        when (effect) {
            is ComposeEffect.OpenCamera -> recorder.camera(effect.on)
            is ComposeEffect.StartRecording -> recorder.start(effect.cameraOn)
            ComposeEffect.StopRecording -> recorder.stop()
            ComposeEffect.CloseCamera -> recorder.camera(false)
            ComposeEffect.DeleteTake -> { review.release(); recorder.deleteTake() }
            ComposeEffect.Upload -> {
                review.pause()
                val (duration, audioOnly) = state.durationMs to !state.cameraOn
                activity.lifecycleScope.launch {
                    val failure = PhoneMessages.send(recorder.take, portal, duration, audioOnly)
                    dispatch(if (failure == null) ComposeEvent.SendOk else ComposeEvent.SendFailed(failure))
                }
            }
            ComposeEffect.Finish -> onFinish()
        }
    }

    fun release() {
        if (released) return
        dispatch(ComposeEvent.Discard)
        released = true
        main.removeCallbacks(tick)
        review.release()
        recorder.release()
        Log.i("ToolkitMessages", "compose closed (${PhoneCalls.state.value.call != null})")
    }

    private companion object { const val TICK_MS = 250L }
}

/** A message's plaintext straight from the sealed file, one 64 KiB chunk at a time. */
internal class SealedSource(private val reader: PhoneMessageCrypto.Reader) : MediaDataSource() {
    override fun getSize(): Long = reader.plainBytes
    @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        var done = 0
        while (done < size) { val n = reader.read(position + done, buffer, offset + done, size - done); if (n <= 0) break; done += n }
        return if (done == 0) -1 else done
    }
    @Synchronized override fun close() = reader.close()
}

/** Our own take (review only). */
internal class PlainSource(file: java.io.File) : MediaDataSource() {
    private val raf = java.io.RandomAccessFile(file, "r")
    override fun getSize(): Long = raf.length()
    @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= raf.length()) return -1
        raf.seek(position)
        return raf.read(buffer, offset, size)
    }
    @Synchronized override fun close() = raf.close()
}

/** MediaPlayer over a [MediaDataSource], bound to the screen's surface. */
internal class MessagePlayer(private val source: () -> MediaDataSource?) {
    private var mp: MediaPlayer? = null
    var playing by mutableStateOf(false)
        private set
    var done by mutableStateOf(false)
        private set
    private var surface: Surface? = null

    fun surface(s: Surface?) { surface = s; mp?.setSurface(s) }

    fun play() {
        val existing = mp
        if (existing != null) { existing.start(); playing = true; return }
        val src = source() ?: return
        val player = MediaPlayer()
        val ok = runCatching {
            player.setDataSource(src)
            surface?.let(player::setSurface)
            player.setOnCompletionListener { playing = false; done = true }
            player.setOnPreparedListener { it.start(); playing = true; done = false }
            player.prepareAsync()
        }.onFailure { Log.w("ToolkitMessages", "playback failed: ${it.javaClass.simpleName}") }.isSuccess
        if (ok) mp = player else runCatching { player.release() }
    }

    fun pause() { mp?.takeIf { playing }?.pause(); playing = false }

    fun release() { runCatching { mp?.release() }; mp = null; playing = false }
}
