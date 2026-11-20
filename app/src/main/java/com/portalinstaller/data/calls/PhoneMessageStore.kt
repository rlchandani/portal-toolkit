/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.portalinstaller.data.calls

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The at-rest data key for messages on this phone: 32 random bytes kept only wrapped by a
 * non-exportable Android Keystore AES key, so a copied file set is useless off this phone.
 */
internal object PhoneMessageKeys {
    private const val ALIAS = "toolkit_vmsg_v1"
    private const val BLOB = "key.bin"
    private const val GCM_BITS = 128
    private const val IV_BYTES = 12
    private const val KEY_BYTES = 32
    @Volatile private var cached: ByteArray? = null

    @Synchronized
    fun dataKey(dir: File): ByteArray {
        cached?.let { return it }
        val blob = File(dir, BLOB)
        val key = if (blob.exists()) unwrap(Base64.decode(blob.readText(), Base64.NO_WRAP)) else create(blob)
        cached = key
        return key
    }

    private fun create(blob: File): ByteArray {
        val key = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, keystoreKey()) }
        val wrapped = c.iv + c.doFinal(key)
        val tmp = File(blob.parentFile, "$BLOB.tmp")
        tmp.writeText(Base64.encodeToString(wrapped, Base64.NO_WRAP))
        if (!tmp.renameTo(blob)) throw java.io.IOException("could not store the message key")
        return key
    }

    private fun unwrap(wrapped: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(GCM_BITS, wrapped, 0, IV_BYTES))
        return c.doFinal(wrapped, IV_BYTES, wrapped.size - IV_BYTES)
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return gen.generateKey()
    }

    /** Delete all also forgets the key: nothing old can be decrypted again. */
    @Synchronized
    fun forget(dir: File) {
        cached = null
        File(dir, BLOB).delete()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }
}

/**
 * Messages from Portals on this phone: `no_backup/toolkit_vmsg/<id>.hpv` sealed at rest
 * ([PhoneMessageCrypto], rest key), plus an index. Never plaintext on disk: playback decrypts
 * chunk by chunk in memory. 30-day expiry ([PhoneMessageList.prune]). Thread-safe.
 */
object PhoneMessageStore {
    private const val TAG = "ToolkitMessages"
    const val DIR = "toolkit_vmsg"
    /** Our own take while composing (deleted on send, cancel, and at startup). */
    const val OUT_DIR = "toolkit_vmsg_out"
    private const val INDEX = "index.json"
    private const val SEALED = ".hpv"
    private const val PART = ".part"
    private val lock = Any()
    private val mutable = MutableStateFlow<List<PhoneMessage>>(emptyList())
    val messages: StateFlow<List<PhoneMessage>> = mutable.asStateFlow()
    private val inflight = HashSet<String>()
    @Volatile private var loaded = false

    fun dir(c: Context): File = File(c.noBackupFilesDir, DIR).apply { mkdirs() }
    fun outDir(c: Context): File = File(c.noBackupFilesDir, OUT_DIR).apply { mkdirs() }
    fun file(c: Context, id: String): File? = if (PhoneMessageList.isId(id)) File(dir(c), id + SEALED) else null

    fun load(c: Context) = synchronized(lock) {
        if (!loaded) {
            mutable.value = PhoneMessageList.decode(runCatching { File(dir(c), INDEX).readText() }.getOrNull())
            loaded = true
        }
        pruneLocked(c)
        outDir(c).listFiles()?.forEach { it.delete() }
    }

    /** Whether we would take [n] now (not a duplicate, room and space). */
    fun accepts(c: Context, n: MessageNotice): Boolean = synchronized(lock) {
        PhoneMessageList.canAccept(mutable.value, n, dir(c).usableSpace) == null && n.id !in inflight
    }

    /**
     * Stores one message from [input] (the transit-sealed bytes from the Portal): every chunk is
     * opened under the transit key and re-sealed under our rest key into `<id>.part`, then renamed.
     * Throws [PhoneMessageCrypto.Tampered] when anything fails authentication or is truncated.
     */
    fun receive(c: Context, householdKey: ByteArray, n: MessageNotice, fromSession: String?, input: InputStream, now: Long): PhoneMessage {
        synchronized(lock) {
            check(PhoneMessageList.canAccept(mutable.value, n, dir(c).usableSpace) == null && inflight.add(n.id)) { "not accepted" }
        }
        val part = File(dir(c), n.id + PART)
        try {
            val rest = PhoneMessageCrypto.restKey(PhoneMessageKeys.dataKey(dir(c)), n.id)
            FileOutputStream(part).use { fos ->
                val out = BufferedOutputStream(fos, PhoneMessageCrypto.SEALED_CHUNK)
                val opener = PhoneMessageCrypto.Opener(PhoneMessageCrypto.transitKey(householdKey, n.id), n.id, n.sealedBytes) { i, final, bytes, len ->
                    out.write(PhoneMessageCrypto.sealChunk(rest, n.id, i, final, bytes, len))
                }
                val buf = ByteArray(PhoneMessageCrypto.SEALED_CHUNK)
                while (true) { val r = input.read(buf); if (r < 0) break; opener.write(buf, 0, r) }
                opener.finish()
                out.flush()
                fos.fd.sync()
            }
            val sealed = File(dir(c), n.id + SEALED)
            if (!part.renameTo(sealed)) throw java.io.IOException("rename failed")
            val m = PhoneMessage(n.id, n.fromName, fromSession, now, n.durationMs, sealed.length(), n.audioOnly)
            synchronized(lock) { save(c, PhoneMessageList.add(mutable.value, m, now)) }
            Log.i(TAG, "message ${n.id.take(4)}… stored (${sealed.length() / 1024} KiB)")
            return m
        } finally {
            part.delete()
            synchronized(lock) { inflight.remove(n.id) }
        }
    }

    fun markSeen(c: Context, id: String) = synchronized(lock) { save(c, PhoneMessageList.markSeen(mutable.value, id)) }

    fun delete(c: Context, id: String) = synchronized(lock) {
        file(c, id)?.delete()
        save(c, mutable.value.filterNot { it.id == id })
    }

    fun deleteAll(c: Context) = synchronized(lock) {
        dir(c).listFiles()?.forEach { it.delete() }
        PhoneMessageKeys.forget(dir(c))
        save(c, emptyList())
    }

    fun bytesUsed(c: Context): Long = dir(c).listFiles()?.filter { it.name.endsWith(SEALED) }?.sumOf { it.length() } ?: 0L

    fun prune(c: Context) = synchronized(lock) { pruneLocked(c) }

    /** A reader over one message's plaintext, decrypting chunks in memory (playback). */
    fun reader(c: Context, m: PhoneMessage): PhoneMessageCrypto.Reader? {
        val f = file(c, m.id)?.takeIf { it.exists() } ?: return null
        return PhoneMessageCrypto.Reader(f, PhoneMessageCrypto.restKey(PhoneMessageKeys.dataKey(dir(c)), m.id), m.id)
    }

    private fun pruneLocked(c: Context) {
        val kept = PhoneMessageList.prune(mutable.value, System.currentTimeMillis())
        val names = kept.map { it.id + SEALED }.toSet() + INDEX + "key.bin"
        dir(c).listFiles()?.filter { it.name !in names && !it.name.endsWith(PART) }?.forEach { it.delete() }
        if (kept != mutable.value) save(c, kept)
    }

    private fun save(c: Context, list: List<PhoneMessage>) {
        mutable.value = list
        val tmp = File(dir(c), "$INDEX.tmp")
        runCatching {
            tmp.writeText(PhoneMessageList.encode(list))
            if (!tmp.renameTo(File(dir(c), INDEX))) throw java.io.IOException("rename failed")
        }.onFailure { Log.w(TAG, "message index not saved: ${it.javaClass.simpleName}") }
    }
}
