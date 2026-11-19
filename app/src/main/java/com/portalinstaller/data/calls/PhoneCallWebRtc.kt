/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.calls

import android.util.Log
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/** PeerConnection.Observer with every callback a no-op, so [PhoneCallMedia] overrides only what it uses. */
internal abstract class PeerObserver : PeerConnection.Observer {
    override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = Unit
    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
    override fun onIceCandidate(candidate: IceCandidate?) = Unit
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
    override fun onAddStream(stream: MediaStream?) = Unit
    override fun onRemoveStream(stream: MediaStream?) = Unit
    override fun onDataChannel(channel: DataChannel?) = Unit
    override fun onRenegotiationNeeded() = Unit
}

internal fun sdpObserver(
    onCreate: (SessionDescription) -> Unit = {},
    onSet: () -> Unit = {},
    onFail: () -> Unit,
) = object : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) = onCreate(desc)
    override fun onSetSuccess() = onSet()
    override fun onCreateFailure(error: String?) { Log.w("ToolkitCallMedia", "sdp create failed: $error"); onFail() }
    override fun onSetFailure(error: String?) { Log.w("ToolkitCallMedia", "sdp set failed: $error"); onFail() }
}
