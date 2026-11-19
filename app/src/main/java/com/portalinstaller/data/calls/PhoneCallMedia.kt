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
 */
class PhoneCallMedia(context: Context, private val events: Events) {
    interface Events {
        fun onConnected()
        fun onDisconnected()
    }

    private val app = context.applicationContext
    private val thread = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "toolkit-call-media") }
    val egl: EglBase = EglBase.create()
    /** The UI points these at its renderers (null target = not shown). */
    val local = ForwardingSink()
    val remote = ForwardingSink()

    private var adm: JavaAudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var videoSource: org.webrtc.VideoSource? = null
    private var audioSource: org.webrtc.AudioSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioTrack: org.webrtc.AudioTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var gathered: (() -> Unit)? = null
    private var savedAudioMode: Int? = null
    @Volatile private var released = false

    init { post(::setUp) }

    fun camera(on: Boolean) = post { if (on) startCamera() else stopCamera() }
    fun mic(on: Boolean) = post { audioTrack?.setEnabled(on) }
    fun speaker(on: Boolean) = post {
        @Suppress("DEPRECATION")
        runCatching { app.getSystemService(AudioManager::class.java)?.isSpeakerphoneOn = on }
    }

    /** The offer SDP (LAN host candidates only, gathering complete) or null. */
    fun createOffer(done: (String?) -> Unit) = post {
        val f = factory ?: return@post done(null)
        val peer = f.createPeerConnection(lanConfig(), observer) ?: return@post done(null)
        pc = peer
        val init = { RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf(STREAM_ID)) }
        peer.addTransceiver(audioTrack, init())
        peer.addTransceiver(videoTrack, init())
        peer.createOffer(sdpObserver(onCreate = { desc ->
            peer.setLocalDescription(sdpObserver(onSet = { post { whenGathered(peer, done) } }, onFail = { done(null) }), desc)
        }, onFail = { done(null) }), MediaConstraints())
    }

    fun applyAnswer(sdp: String, done: (Boolean) -> Unit) = post {
        val peer = pc ?: return@post done(false)
        peer.setRemoteDescription(sdpObserver(onSet = { done(true) }, onFail = { done(false) }),
            SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    fun release() {
        if (released) return
        released = true
        local.target = null
        remote.target = null
        thread.execute {
            runCatching { stopCamera() }
            runCatching { pc?.dispose() }
            runCatching { videoTrack?.dispose(); audioTrack?.dispose() }
            runCatching { videoSource?.dispose(); audioSource?.dispose() }
            runCatching { factory?.dispose() }
            runCatching { adm?.release() }
            runCatching { egl.release() }
            restoreAudio()
            pc = null; factory = null; adm = null
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

    private fun whenGathered(peer: PeerConnection, done: (String?) -> Unit) {
        val finish = { done(peer.localDescription?.description?.let(CallSdp::lanOnly)?.takeIf(CallSdp::hasLanCandidate)) }
        if (peer.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) { finish(); return }
        gathered = finish
        thread.schedule({ gathered?.let { gathered = null; it() } }, GATHER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private val observer = object : PeerObserver() {
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) post { gathered?.let { gathered = null; it() } }
        }
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> events.onConnected()
                PeerConnection.PeerConnectionState.DISCONNECTED, PeerConnection.PeerConnectionState.FAILED -> events.onDisconnected()
                else -> Unit
            }
        }
        override fun onTrack(transceiver: RtpTransceiver?) {
            (transceiver?.receiver?.track() as? VideoTrack)?.addSink(remote)
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
