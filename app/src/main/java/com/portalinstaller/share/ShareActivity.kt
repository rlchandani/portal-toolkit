/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.portalinstaller.share.music.MusicLink
import com.portalinstaller.share.music.MusicLinks
import com.portalinstaller.share.music.MusicShareHost
import com.portalinstaller.ui.theme.PortalTheme
import com.portalinstaller.util.AppLanguageManager

/**
 * Android share-sheet target for ACTION_SEND / ACTION_SEND_MULTIPLE (image/\*, text/plain).
 * Exported; it only ever reads what the sender explicitly granted, and copies it before sending.
 */
open class ShareActivity : AppCompatActivity() {
    /** Only the non-exported [ShareJobActivity] may open an existing job (from our notifications). */
    protected open val opensJobs: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.applySavedLanguage(this)
        super.onCreate(savedInstanceState)
        val hub = ShareHub.get(this)
        val jobId = intent.getStringExtra(EXTRA_JOB)?.takeIf { opensJobs && hub.runner.job(it) != null }
        val music = if (jobId == null) musicLink(intent) else null
        if (music != null) return showMusic(hub, music)
        val payload = if (jobId != null) null else when (val parsed = parse(intent)) {
            is ShareParse.Ok -> parsed.payload
            is ShareParse.Rejected -> { Toast.makeText(this, parsed.message, Toast.LENGTH_LONG).show(); finish(); return }
        }
        setContent {
            PortalTheme(darkTheme = androidx.compose.foundation.isSystemInDarkTheme()) {
                ShareSheetHost(hub = hub, payload = payload, openJobId = jobId, onClose = ::finish)
            }
        }
    }

    /** A shared YouTube / YouTube Music link opens "Play on a Portal" instead of the note/link sheet. */
    private fun musicLink(intent: Intent): MusicLink? {
        if (intent.action != Intent.ACTION_SEND || intent.type?.lowercase()?.startsWith("text/") != true) return null
        return MusicLinks.find(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
    }

    private fun showMusic(hub: ShareHub, link: MusicLink) {
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        setContent {
            PortalTheme(darkTheme = androidx.compose.foundation.isSystemInDarkTheme()) {
                var asLink by remember { mutableStateOf(false) }
                if (asLink) ShareSheetHost(hub, SharePayload.Link(link.url, subject?.take(ShareIntentParser.MAX_TITLE_CHARS)), null, ::finish)
                else MusicShareHost(hub, link, subject, onSendAsLink = { asLink = true }, onClose = ::finish)
            }
        }
    }

    private fun parse(intent: Intent): ShareParse = ShareIntentParser.parse(intent.action, intent.type,
        intent.getCharSequenceExtra(Intent.EXTRA_TEXT), intent.getStringExtra(Intent.EXTRA_SUBJECT),
        streams(intent).map(Uri::toString))

    private fun streams(intent: Intent): List<Uri> {
        val extras = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        } else listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        val clip = intent.clipData?.let { c -> (0 until c.itemCount).mapNotNull { c.getItemAt(it).uri } }.orEmpty()
        return (extras.orEmpty() + clip).distinct()
    }

    companion object {
        const val EXTRA_JOB = "com.portalinstaller.share.JOB"
        fun openJob(context: Context, jobId: String): Intent = Intent(context, ShareJobActivity::class.java)
            .putExtra(EXTRA_JOB, jobId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

/** Not exported: notifications reopen a job's sheet through here, so other apps can't. */
class ShareJobActivity : ShareActivity() {
    override val opensJobs: Boolean = true
}
