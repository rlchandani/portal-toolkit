package com.portalinstaller.data

import dadb.AdbShellResponse
import dadb.Dadb
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class InstallTargetTest {
    private fun device(sdk: String, abis: String) = Proxy.newProxyInstance(
        Dadb::class.java.classLoader, arrayOf(Dadb::class.java)
    ) { _, method, args ->
        check(method.name == "shell")
        AdbShellResponse(when (args!![0]) {
            "getprop ro.build.version.sdk" -> sdk
            "getprop ro.product.cpu.abilist" -> abis
            "getprop ro.product.cpu.abi" -> "armeabi-v7a"
            else -> error("Unexpected property")
        }, "", 0)
    } as Dadb

    @Test fun `install target uses API and ABI properties from originating transport`() = runTest {
        assertEquals(InstallTarget(28, listOf("arm64-v8a", "armeabi-v7a")),
            PortalConnectionManager().readInstallTarget(device("28", "arm64-v8a,armeabi-v7a")))
        assertEquals(InstallTarget(29, listOf("armeabi-v7a")),
            PortalConnectionManager().readInstallTarget(device("29", "")))
        assertTrue(runCatching { PortalConnectionManager().readInstallTarget(device("", "")) }.isFailure)
    }

    @Test fun `native ABI scan reads packaged libraries rather than phone ABI`() {
        val apk = Files.createTempFile("native-apk", ".apk").toFile()
        try {
            ZipOutputStream(apk.outputStream()).use { zip ->
                listOf("lib/arm64-v8a/libone.so", "lib/x86/libone.so", "assets/x86/info.so").forEach {
                    zip.putNextEntry(ZipEntry(it))
                    zip.write(1)
                    zip.closeEntry()
                }
            }
            assertEquals(setOf("arm64-v8a", "x86"), InstallPolicy.nativeAbis(apk))
        } finally { apk.delete() }
    }
}
