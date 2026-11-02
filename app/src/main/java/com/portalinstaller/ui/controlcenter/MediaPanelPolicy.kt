/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.ui.controlcenter

import com.portalinstaller.viewmodel.CompanionMusicUiState

/** An older Portal's result can select fallback only for the session that produced it. */
internal fun usesLegacyPlayback(deviceKey: String, music: CompanionMusicUiState): Boolean =
    music.deviceKey == deviceKey && music.unsupported
