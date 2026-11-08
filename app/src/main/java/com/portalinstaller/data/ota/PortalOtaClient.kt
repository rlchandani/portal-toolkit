/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.ota

import com.portalinstaller.data.companion.CompanionClient
import com.portalinstaller.data.companion.CompanionException
import com.portalinstaller.data.companion.CompanionSession
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * One paired Portal's Hearthpane updates (`/remote/ota`): GET reads the state, POST
 * `{action: "install"}` asks it to update now. A 404 is an older Hearthpane: unsupported, silently.
 */
class PortalOtaClient(
    private val client: CompanionClient,
    private val answerMs: Long = ANSWER_MS,
) {
    suspend fun read(session: CompanionSession): RoomOta = try {
        PortalOtaParser.parse(call { client.get(session, ROUTE) })?.let { RoomOta.Ready(it) } ?: RoomOta.Unsupported
    } catch (_: TimeoutCancellationException) { RoomOta.Offline }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (e: CompanionException) {
        when {
            e.error.status == NOT_FOUND -> RoomOta.Unsupported
            e.error.code == UNAUTHORIZED -> RoomOta.Failed(PortalOtaCopy.repair(session.device.name))
            else -> RoomOta.Failed(PortalOtaCopy.installFailed(session.device.name))
        }
    }
    catch (_: IOException) { RoomOta.Offline }

    /** Null when the Portal accepted; else the message to show. */
    suspend fun install(session: CompanionSession): String? = action(session, INSTALL)

    /** Null when the Portal accepted; else the message to show. */
    suspend fun check(session: CompanionSession): String? = action(session, CHECK)

    private suspend fun action(session: CompanionSession, action: String): String? {
        val room = session.device.name
        return try {
            call { client.post(session, ROUTE, JSONObject().put("action", action)) }
            null
        } catch (_: TimeoutCancellationException) { PortalOtaCopy.offline(room) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (e: CompanionException) {
            when {
                e.error.status == NOT_FOUND -> PortalOtaCopy.unsupported(room)
                e.error.code == UNAUTHORIZED -> PortalOtaCopy.repair(room)
                else -> PortalOtaCopy.installFailed(room)
            }
        }
        catch (_: IOException) { PortalOtaCopy.offline(room) }
    }

    private suspend fun call(block: suspend () -> JSONObject): JSONObject = withTimeout(answerMs) { block() }

    companion object {
        const val ROUTE = "ota"
        const val ANSWER_MS = 3_500L
        private const val INSTALL = "install"
        private const val CHECK = "check"
        private const val NOT_FOUND = 404
        private const val UNAUTHORIZED = "unauthorized"
    }
}
