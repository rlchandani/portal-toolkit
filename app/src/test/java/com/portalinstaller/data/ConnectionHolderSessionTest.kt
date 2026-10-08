package com.portalinstaller.data

import dadb.Dadb
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.CopyOnWriteArrayList

class ConnectionHolderSessionTest {
    private fun device() = Proxy.newProxyInstance(Dadb::class.java.classLoader, arrayOf(Dadb::class.java)) { proxy, method, args ->
        when (method.name) {
            "close" -> null
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            else -> error("Unexpected ${method.name}")
        }
    } as Dadb

    @Test fun `stale session cannot clear replacement and is never reused after reconnect`() {
        val holder = ConnectionHolder()
        val origin = device()
        holder.set(origin, "one")
        val session = holder.requireSession()
        val replacement = device()
        holder.set(replacement, "two")
        assertFalse(holder.clearIfCurrent(session))
        assertSame(replacement, holder.require())
        holder.set(origin, "one again")
        assertFalse(holder.isCurrent(session))
        holder.clear()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `connection loss stops session owned work`() = runTest {
        val holder = ConnectionHolder()
        holder.set(device(), "one")
        var stopped = false
        val job = holder.launchForSession(this, holder.requireSession()) {
            try { awaitCancellation() } finally { stopped = true }
        }
        runCurrent()
        holder.clear()
        runCurrent()
        assertTrue(job.isCancelled)
        assertTrue(stopped)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `queued mirror control never redirects to a replacement device`() = runTest {
        val commands = CopyOnWriteArrayList<String>()
        fun target(label: String) = Proxy.newProxyInstance(Dadb::class.java.classLoader, arrayOf(Dadb::class.java)) { proxy, method, args ->
            if (method.name == "shell") { commands += label; dadb.AdbShellResponse("", "", 0) }
            else when (method.name) {
                "close" -> null
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                else -> error(method.name)
            }
        } as Dadb
        val holder = ConnectionHolder()
        holder.set(target("origin"), "origin")
        val session = holder.requireSession()
        val job = holder.launchForSession(this, session) {
            withContext(Dispatchers.IO) {
                holder.ensureCurrent(session)
                session.dadb.shell("input keyevent 4")
            }
        }
        holder.set(target("replacement"), "replacement")
        runCurrent()
        job.join()
        assertTrue(commands.isEmpty())
        holder.clear()
    }
}
