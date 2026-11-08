/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.music

/** Every user-facing line of the Music page, so the copy stays consistent and testable. */
internal object MusicCopy {
    fun update(room: String) = "Update Hearthpane on $room to control music from your phone."
    fun offline(room: String) = "$room isn’t answering. Check it’s on and on Wi-Fi."
    fun repair(room: String) = "Pair this phone with $room again."
    fun failed(room: String) = "$room couldn’t do that. Try again."
    fun declined(room: String) = "$room couldn’t play right now."
    fun nothingQueued(room: String) = "Nothing is queued on $room. Share a song from YouTube to start."
    fun unknownEntry(room: String) = "That song isn’t in the queue on $room any more."
    fun opening(room: String) = "Opening Music on $room…"
    fun volumeFixed(room: String) = "Volume on $room is controlled by its output device."
    fun quietAskPlay(rooms: String, until: String?) =
        "It’s quiet hours in $rooms${until?.let { " until $it" }.orEmpty()}. Play anyway, quietly?"
    fun quietAskVolume(room: String, until: String?) =
        "It’s quiet hours in $room${until?.let { " until $it" }.orEmpty()}. Turn it up anyway?"
    const val NO_ROOMS = "Pair a Portal to control its music from here."
    const val NOTHING_PLAYING = "Nothing playing"
    const val NOT_ON_SCREEN = "Music isn’t open on the Portal. Play opens it."
}
