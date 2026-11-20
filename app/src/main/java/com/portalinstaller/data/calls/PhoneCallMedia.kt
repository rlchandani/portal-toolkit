/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * One call's WebRTC media on the phone. The phone is always the offerer: [createOffer] builds a
 * non-trickle offer with only home-Wi-Fi host candidates, [applyAnswer] applies the Portal's
 * verified answer. Front camera through Camera2, VOICE_COMMUNICATION mic with the phone's
 * hardware echo canceller, HW H.264/VP8. All WebRTC work runs on one thread; [release] tears
 * everything down. Nothing is recorded or saved.
 *
 * Calling 2b: one peer connection per Portal in the call (keyed by session id), all sharing the
 * one camera and mic; [close] drops a single Portal. At 3+ people every send drops to half
 * resolution and a lower bitrate, as on the Portal, so a phone in a group stays cool.
 */
class PhoneCallMedia(context: Context, private val events: Events) {
    interface Events {
        fun onConnected(peer: String)
        fun onDisconnected(peer: String)
    }

    private val app = context.applicationContext
    private val thread = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "toolkit-call-media") }
    val egl: EglBase = EglBase.create()
    /** The UI points these at its renderers (null target = not shown). */
    val local = ForwardingSink()
    /** The first Portal's picture (1:1 screens); group tiles use [remote] per peer. */
    val remote = ForwardingSink()
    private val remotes = java.util.concurrent.ConcurrentHashMap<String, ForwardingSink>()
    @Volatile private var first: String? = null

    /** The sink for [peer]'s picture (stable for the call; frames flow once its track arrives). */
    fun remote(peer: String): ForwardingSink = remotes.getOrPut(peer) { ForwardingSink() }

    /** Which peer [remote] shows (the call's first Portal; it changes when the first one leaves). */
    fun primary(peer: String) { first = peer; remote.target = null }

    private var adm: JavaAudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private val pcs = HashMap<String, PeerConnection>()
    private var people = 2
    private var videoSource: org.webrtc.VideoSource? = null
    private var audioSource: org.webrtc.AudioSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioTrack: org.webrtc.AudioTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private val gathered = HashMap<String, () -> Unit>()
    private var savedAudioMode: Int? = null
    @Volatile private var released = false

    init { post(::setUp) }

    fun camera(on: Boolean) = post { if (on) startCamera() else stopCamera() }
    fun mic(on: Boolean) = post { audioTrack?.setEnabled(on) }
    fun speaker(on: Boolean) = post {
        @Suppress("DEPRECATION")
        runCatching { app.getSystemService(AudioManager::class.java)?.isSpeakerphoneOn = on }
    }

    /** The offer SDP to [key] (LAN host candidates only, gathering complete) or null. */
    fun createOffer(key: String, done: (String?) -> Unit) = post {
        val f = factory ?: return@post done(null)
        pcs.remove(key)?.let { runCatching { it.dispose() } }
        val peer = f.createPeerConnection(lanConfig(), observer(key)) ?: return@post done(null)
        pcs[key] = peer
        val init = { RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf(STREAM_ID)) }
        peer.addTransceiver(audioTrack, init())
        peer.addTransceiver(videoTrack, init())
        applySendProfile(peer)
        peer.createOffer(sdpObserver(onCreate = { desc ->
            peer.setLocalDescription(sdpObserver(onSet = { post { whenGathered(key, peer, done) } }, onFail = { done(null) }), desc)
        }, onFail = { done(null) }), MediaConstraints())
    }

    fun applyAnswer(key: String, sdp: String, done: (Boolean) -> Unit) = post {
        val peer = pcs[key] ?: return@post done(false)
        peer.setRemoteDescription(sdpObserver(onSet = { done(true) }, onFail = { done(false) }),
            SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    /** One Portal left the call: close only its link (camera and mic stay for the others). */
    fun close(key: String) = post {
        gathered.remove(key)
        pcs.remove(key)?.let { runCatching { it.dispose() } }
        remotes.remove(key)?.target = null
    }

    /** People in the call (this phone included): 3+ sends half resolution at a lower bitrate. */
    fun groupSize(n: Int) = post {
        if (n == people) return@post
        people = n
        pcs.values.forEach(::applySendProfile)
    }

    private fun applySendProfile(peer: PeerConnection) {
        val sender = peer.senders.firstOrNull { it.track()?.kind() == "video" } ?: return
        val params = sender.parameters
        params.encodings.forEach {
            it.scaleResolutionDownBy = if (people >= 3) GROUP_SCALE else 1.0
            it.maxBitrateBps = if (people >= 3) GROUP_MAX_BPS else SOLO_MAX_BPS
        }
        runCatching { sender.parameters = params }
    }

    fun release() {
        if (released) return
        released = true
        local.target = null
        remote.target = null
        thread.execute {
            runCatching { stopCamera() }
            pcs.values.forEach { runCatching { it.dispose() } }
            pcs.clear()
            remotes.values.forEach { it.target = null }
            runCatching { videoTrack?.dispose(); audioTrack?.dispose() }
            runCatching { videoSource?.dispose(); audioSource?.dispose() }
            runCatching { factory?.dispose() }
            runCatching { adm?.release() }
            runCatching { egl.release() }
            restoreAudio()
            factory = null; adm = null
            Log.i(TAG, "media released")
        }
        thread.shutdown()
    }

    private fun post(block: () -> Unit) {
        if (released) return
        runCatching { thread.execute { if (!released) runCatching(block).onFailure { Log.w(TAG, "media task failed", it) } } }
    }

    private fun setUp() {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
        val module = JavaAudioDeviceModule.builder(app)
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        adm = module
        val f = PeerConnectionFactory.builder().setAudioDeviceModule(module)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        factory = f
        val vs = f.createVideoSource(false)
        videoSource = vs
        videoTrack = f.createVideoTrack(VIDEO_ID, vs).also { it.addSink(local); it.setEnabled(false) }
        val audio = f.createAudioSource(MediaConstraints())
        audioSource = audio
        audioTrack = f.createAudioTrack(AUDIO_ID, audio)
        useCommunicationAudio()
    }

    private fun startCamera() {
        if (capturer != null) return
        if (app.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val enumerator = Camera2Enumerator(app)
        val names = runCatching { enumerator.deviceNames.toList() }.getOrDefault(emptyList())
        val name = names.firstOrNull { enumerator.isFrontFacing(it) } ?: names.firstOrNull() ?: return
        val source = videoSource ?: return
        val cam = enumerator.createCapturer(name, null) ?: return
        val h = SurfaceTextureHelper.create("toolkit-call-capture", egl.eglBaseContext)
        cam.initialize(h, app, source.capturerObserver)
        cam.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)
        capturer = cam
        helper = h
        videoTrack?.setEnabled(true)
    }

    private fun stopCamera() {
        videoTrack?.setEnabled(false)
        val cam = capturer ?: return
        capturer = null
        runCatching { cam.stopCapture() }
        runCatching { cam.dispose() }
        runCatching { helper?.dispose() }
        helper = null
    }

    private fun whenGathered(key: String, peer: PeerConnection, done: (String?) -> Unit) {
        val finish = { done(peer.localDescription?.description?.let(CallSdp::lanOnly)?.takeIf(CallSdp::hasLanCandidate)) }
        if (peer.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) { finish(); return }
        gathered[key] = finish
        thread.schedule({ post { gathered.remove(key)?.invoke() } }, GATHER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun observer(key: String) = object : PeerObserver() {
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) post { gathered.remove(key)?.invoke() }
        }
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> events.onConnected(key)
                PeerConnection.PeerConnectionState.DISCONNECTED, PeerConnection.PeerConnectionState.FAILED -> events.onDisconnected(key)
                else -> Unit
            }
        }
        override fun onTrack(transceiver: RtpTransceiver?) {
            (transceiver?.receiver?.track() as? VideoTrack)?.addSink(PeerSink(key))
        }
    }

    /** Frames from one peer: to its own tile, and to [remote] while it is the call's first Portal. */
    private inner class PeerSink(private val key: String) : VideoSink {
        override fun onFrame(frame: org.webrtc.VideoFrame) {
            remote(key).onFrame(frame)
            if (first == key) remote.onFrame(frame)
        }
    }

    private fun useCommunicationAudio() {
        val am = app.getSystemService(AudioManager::class.java) ?: return
        savedAudioMode = am.mode
        runCatching {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION") am.isSpeakerphoneOn = true
        }
    }

    private fun restoreAudio() {
        val am = app.getSystemService(AudioManager::class.java) ?: return
        val mode = savedAudioMode ?: return
        runCatching {
            am.mode = mode
            @Suppress("DEPRECATION") am.isSpeakerphoneOn = false
        }
    }

    /** Hands frames to whichever renderer is showing now (or drops them). */
    class ForwardingSink : VideoSink {
        @Volatile var target: VideoSink? = null
        override fun onFrame(frame: org.webrtc.VideoFrame) { target?.onFrame(frame) }
    }

    companion object {
        private const val TAG = "ToolkitCallMedia"
        private const val VIDEO_ID = "tk-video"
        private const val AUDIO_ID = "tk-audio"
        private const val STREAM_ID = "tk"
        private const val CAPTURE_WIDTH = 1280
        private const val CAPTURE_HEIGHT = 720
        private const val CAPTURE_FPS = 30
        private const val GATHER_TIMEOUT_MS = 2_500L
        private const val GROUP_SCALE = 2.0
        private const val GROUP_MAX_BPS = 700_000
        private const val SOLO_MAX_BPS = 2_500_000

        /** Home Wi-Fi only: no STUN or TURN, UDP, one gathering pass. */
        fun lanConfig(): PeerConnection.RTCConfiguration = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            keyType = PeerConnection.KeyType.ECDSA
        }
    }
}
