/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.doorbell

import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.CompanionException
import com.portalinstaller.data.companion.CompanionSessionStore
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

/**
 * Recent doorbell events from every paired Portal (`/remote/doorbell`). A Portal that is away,
 * offline or on an older Hearthpane (404) just contributes nothing. Read only while the app is in
 * the foreground ([com.portalinstaller.ui.doorbell.DoorbellBannerHost]); there is no background push.
 */
class DoorbellRepository(
    private val client: CompanionClient,
    private val store: CompanionSessionStore,
    private val log: (String) -> Unit = {},
) {
    /** One list per Portal that answered; merge with [DoorbellBannerPolicy]. */
    suspend fun read(): List<List<DoorbellEvent>> {
        val sessions = try { store.load() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            log("could not read paired Portals: ${e.javaClass.simpleName}")
            return emptyList()
        }
        return coroutineScope {
            sessions.map { session ->
                async {
                    try {
                        withTimeout(ANSWER_MS) { DoorbellEvent.parseList(client.get(session, ROUTE).optJSONArray("events")) }
                    } catch (e: TimeoutCancellationException) { null }
                    catch (e: CancellationException) { throw e }
                    catch (e: CompanionException) { null }
                    catch (e: IOException) { null }
                }
            }.awaitAll().filterNotNull()
        }
    }

    companion object {
        const val ROUTE = "doorbell"
        const val ANSWER_MS = 3_000L
    }
}
