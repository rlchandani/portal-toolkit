/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.calls

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import java.io.File

/**
 * Records one message on the phone: front camera (Camera2) into MediaRecorder, H.264 at up to
 * 1280×720 ~1.6 Mbps plus AAC mono, the same as a Portal's message. Camera off records sound
 * only. The take lives in private no-backup temp ([PhoneMessageStore.OUT_DIR]) and is deleted on
 * Record again, Close, and after a confirmed send. Camera and mic are open only while this screen
 * is in front (the activity releases everything in onStop).
 */
class PhoneMessageRecorder(context: Context, private val onRecorded: (ok: Boolean, durationMs: Long) -> Unit) {
    private val app = context.applicationContext
    val take: File = File(PhoneMessageStore.outDir(app), "take.mp4")
    private val thread = HandlerThread("toolkit-vmsg-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private var preview: SurfaceTexture? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var recording = false
    private var startedAt = 0L
    private var wantCamera = false
    @Volatile private var released = false

    /** The self view's texture appeared (or went: null). */
    fun preview(texture: SurfaceTexture?) = handler.post {
        preview = texture?.apply { setDefaultBufferSize(SIZE.width, SIZE.height) }
        if (texture != null && wantCamera) restartSession(withRecorder = recording) else closeSession()
    }

    fun camera(on: Boolean) = handler.post {
        wantCamera = on
        if (on) open() else if (!recording) closeCamera()
    }

    fun start(cameraOn: Boolean) = handler.post {
        if (released || recording) return@post
        take.delete()
        // No camera frames (busy, or not allowed): record sound only rather than an empty picture.
        val video = cameraOn && device != null
        val r = newRecorder(video) ?: return@post onRecorded(false, 0)
        recorder = r
        if (video) restartSession(withRecorder = true) { begin(r) } else begin(r)
    }

    fun stop() = handler.post { finish(ok = true) }

    fun deleteTake() = handler.post { take.delete() }

    fun release() {
        if (released) return
        handler.post {
            finish(ok = false)
            closeCamera()
            take.delete()
            released = true
            thread.quitSafely()
        }
    }

    private fun begin(r: MediaRecorder) {
        runCatching { r.start(); recording = true; startedAt = SystemClock.elapsedRealtime() }
            .onFailure { Log.w(TAG, "recorder start failed: ${it.javaClass.simpleName}"); finish(ok = false) }
    }

    private fun finish(ok: Boolean) {
        val r = recorder ?: return
        recorder = null
        val wasRecording = recording
        recording = false
        val duration = SystemClock.elapsedRealtime() - startedAt
        val stopped = wasRecording && runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        if (wantCamera && device != null && !released) restartSession(withRecorder = false)
        if (ok || wasRecording) onRecorded(ok && stopped && take.length() > 0, if (stopped) duration else 0)
    }

    private fun newRecorder(video: Boolean): MediaRecorder? = runCatching {
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(app) else MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        if (video) r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setOutputFile(take.absolutePath)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1)
        r.setAudioSamplingRate(AUDIO_HZ)
        r.setAudioEncodingBitRate(AUDIO_BPS)
        if (video) {
            r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            r.setVideoSize(SIZE.width, SIZE.height)
            r.setVideoFrameRate(FPS)
            r.setVideoEncodingBitRate(VIDEO_BPS)
            r.setOrientationHint(orientation())
        }
        r.setMaxDuration(PhoneMessageLimits.MAX_DURATION_MS.toInt())
        r.setMaxFileSize(PhoneMessageLimits.MAX_BYTES)
        r.setOnInfoListener { _, what, _ ->
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                handler.post { finish(ok = true) }
            }
        }
        r.prepare()
        r
    }.onFailure { Log.w(TAG, "recorder not prepared: ${it.javaClass.simpleName}") }.getOrNull()

    @SuppressLint("MissingPermission")
    private fun open() {
        if (released || device != null) return
        if (app.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val cm = app.getSystemService(CameraManager::class.java) ?: return
        val id = frontCamera(cm) ?: return
        runCatching {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (released || !wantCamera) { camera.close(); return }
                    device = camera
                    restartSession(withRecorder = recording)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); if (device === camera) device = null }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); if (device === camera) device = null }
            }, handler)
        }.onFailure { Log.w(TAG, "camera not opened: ${it.javaClass.simpleName}") }
    }

    private fun restartSession(withRecorder: Boolean, then: () -> Unit = {}) {
        closeSession()
        val cam = device ?: return then()
        val targets = buildList {
            preview?.let { add(Surface(it)) }
            if (withRecorder) recorder?.surface?.let(::add)
        }
        if (targets.isEmpty()) return then()
        runCatching {
            @Suppress("DEPRECATION")
            cam.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (released) { s.close(); return }
                    session = s
                    val request = cam.createCaptureRequest(if (withRecorder) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
                    targets.forEach(request::addTarget)
                    request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    runCatching { s.setRepeatingRequest(request.build(), null, handler) }
                    then()
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { Log.w(TAG, "camera session failed"); then() }
            }, handler)
        }.onFailure { Log.w(TAG, "session not created: ${it.javaClass.simpleName}"); then() }
    }

    private fun closeSession() { runCatching { session?.close() }; session = null }

    private fun closeCamera() {
        closeSession()
        runCatching { device?.close() }
        device = null
    }

    private fun frontCamera(cm: CameraManager): String? = runCatching {
        cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }
            ?: cm.cameraIdList.firstOrNull()
    }.getOrNull()

    /** Upright on a phone held normally: the front sensor's own orientation. */
    private fun orientation(): Int = runCatching {
        val cm = app.getSystemService(CameraManager::class.java)
        frontCamera(cm!!)?.let { cm.getCameraCharacteristics(it).get(CameraCharacteristics.SENSOR_ORIENTATION) } ?: FRONT_DEFAULT
    }.getOrDefault(FRONT_DEFAULT)

    private companion object {
        const val TAG = "ToolkitMessages"
        val SIZE = Size(1280, 720)
        const val FPS = 30
        const val VIDEO_BPS = 1_600_000
        const val AUDIO_BPS = 64_000
        const val AUDIO_HZ = 44_100
        const val FRONT_DEFAULT = 270
    }
}
