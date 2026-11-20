/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.calls

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.portalinstaller.data.calls.PhoneCallMedia
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * A WebRTC renderer bound to one of the call's sinks for as long as it is composed. [overlay]: the
 * self view, stacked above the remote SurfaceView and mirrored like a mirror.
 */
@Composable
internal fun CallVideo(media: PhoneCallMedia, local: Boolean, modifier: Modifier = Modifier, peer: String? = null) {
    // Group tiles show one Portal each ([peer]); 1:1 shows the first Portal.
    val sink = if (local) media.local else peer?.let(media::remote) ?: media.remote
    AndroidView(
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(media.egl.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                if (local) { setMirror(true); setZOrderMediaOverlay(true) }
                sink.target = this
            }
        },
        modifier = modifier,
        onRelease = { view ->
            if (sink.target === view) sink.target = null
            view.release()
        },
    )
}
