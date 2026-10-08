/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data

import android.content.Context
import android.content.ContextWrapper
import dadb.AdbShellResponse
import dadb.Dadb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class InstallMaintenanceSafetyTest {
    // These shell-only paths never use Context. Bypass android.jar's throwing constructor;
    // any accidental Android call still throws, rather than acquiring a real device context.
    private fun unusedContext(): Context {
        val type = Class.forName("sun.misc.Unsafe")
        val field = type.getDeclaredField("theUnsafe").apply { isAccessible = true }
        return type.getMethod("allocateInstance", Class::class.java)
            .invoke(field.get(null), ContextWrapper::class.java) as Context
    }

    private class Device {
        val data = mutableMapOf("com.example.music" to "selected-data", "com.example.music.beta" to "unrelated-data")
        val installed = data.keys.toMutableSet()
        val stopped = mutableSetOf<String>()
        val commands = mutableListOf<String>()
        var reject = false
        var cancel = false
        val dadb = Proxy.newProxyInstance(Dadb::class.java.classLoader, arrayOf(Dadb::class.java)) { proxy, method, args ->
            when (method.name) {
                "shell" -> {
                    val command = args!![0] as String
                    commands += command
                    val packageName = Regex("'([^']*)'").find(command)?.groupValues?.get(1)
                        ?: error("Package target must be shell quoted")
                    if (cancel) throw CancellationException("cancelled before fake mutation")
                    if (reject) AdbShellResponse("", "Failure [not permitted]", 1)
                    else {
                        when {
                            command.startsWith("pm clear ") -> data.remove(packageName)
                            command.startsWith("pm uninstall ") -> { installed.remove(packageName); data.remove(packageName) }
                            command.startsWith("am force-stop ") -> stopped.add(packageName)
                            else -> error("Unexpected destructive command: $command")
                        }
                        AdbShellResponse("Success", "", 0)
                    }
                }
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "MaintenanceFakeDevice"
                else -> error("Unexpected transport operation: ${method.name}")
            }
        } as Dadb
    }

    @Test fun `clear data affects only exact package in fake transport`() = runTest {
        val device = Device()
        assertTrue(PortalConnectionManager().clearAppData(unusedContext(), device.dadb, "com.example.music").isSuccess)
        assertEquals(listOf("pm clear 'com.example.music'"), device.commands)
        assertEquals(mapOf("com.example.music.beta" to "unrelated-data"), device.data)
        assertEquals(setOf("com.example.music", "com.example.music.beta"), device.installed)
    }

    @Test fun `uninstall affects only exact package in fake transport`() = runTest {
        val device = Device()
        assertTrue(PortalConnectionManager().uninstallApp(unusedContext(), device.dadb, "com.example.music").isSuccess)
        assertEquals(listOf("pm uninstall 'com.example.music'"), device.commands)
        assertEquals(setOf("com.example.music.beta"), device.installed)
        assertEquals(mapOf("com.example.music.beta" to "unrelated-data"), device.data)
    }

    @Test fun `force stop never clears data or targets similar package`() = runTest {
        val device = Device()
        assertTrue(PortalConnectionManager().forceStopApp(unusedContext(), device.dadb, "com.example.music").isSuccess)
        assertEquals(listOf("am force-stop 'com.example.music'"), device.commands)
        assertEquals(setOf("com.example.music"), device.stopped)
        assertEquals(mapOf("com.example.music" to "selected-data", "com.example.music.beta" to "unrelated-data"), device.data)
    }

    @Test fun `failed or cancelled clear data has no fake device mutations or success outcome`() = runTest {
        listOf(Device().apply { reject = true }, Device().apply { cancel = true }).forEach { device ->
            assertTrue(PortalConnectionManager().clearAppData(unusedContext(), device.dadb, "com.example.music").isFailure)
            assertEquals(mapOf("com.example.music" to "selected-data", "com.example.music.beta" to "unrelated-data"), device.data)
            assertEquals(setOf("com.example.music", "com.example.music.beta"), device.installed)
            assertEquals(listOf("pm clear 'com.example.music'"), device.commands)
        }
    }

    @Test fun `failed force stop retains all data`() = runTest {
        val device = Device().apply { reject = true }
        assertTrue(PortalConnectionManager().forceStopApp(unusedContext(), device.dadb, "com.example.music").isFailure)
        assertTrue(device.stopped.isEmpty())
        assertEquals(mapOf("com.example.music" to "selected-data", "com.example.music.beta" to "unrelated-data"), device.data)
    }
}
